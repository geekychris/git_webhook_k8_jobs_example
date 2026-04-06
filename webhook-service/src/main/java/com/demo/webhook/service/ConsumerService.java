package com.demo.webhook.service;

import com.demo.webhook.client.SidecarClient;
import com.demo.webhook.model.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ConsumerService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerService.class);

    private final QueueService queueService;
    private final JobLauncherService jobLauncherService;
    private final SidecarClient sidecarClient;

    @Value("${consumer.max-concurrent-jobs:2}")
    private int maxConcurrentJobs;

    public ConsumerService(QueueService queueService, JobLauncherService jobLauncherService, SidecarClient sidecarClient) {
        this.queueService = queueService;
        this.jobLauncherService = jobLauncherService;
        this.sidecarClient = sidecarClient;
    }

    @Scheduled(fixedDelay = 3000)
    public void pollQueue() {
        // First, check and update statuses of running jobs
        jobLauncherService.checkJobStatuses();

        int activeCount = queueService.activeJobCount();
        long queueSize = queueService.queueSize();

        if (queueSize == 0) return;

        if (activeCount >= maxConcurrentJobs) {
            log.info("At capacity: {}/{} jobs running, {} waiting in queue",
                    activeCount, maxConcurrentJobs, queueSize);
            return;
        }

        int slotsAvailable = maxConcurrentJobs - activeCount;
        log.info("Consumer: {}/{} slots used, {} in queue, launching up to {}",
                activeCount, maxConcurrentJobs, queueSize, slotsAvailable);

        for (int i = 0; i < slotsAvailable; i++) {
            WebhookEvent event = queueService.dequeue();
            if (event == null) break;

            try {
                event.setStatus("PROCESSING");
                queueService.logEvent(event);

                sidecarClient.audit("job_launched", Map.of(
                        "event_id", event.getId(),
                        "repository", event.getRepository(),
                        "pr_number", event.getPrNumber()
                ));

                jobLauncherService.launchJob(event);
            } catch (Exception e) {
                log.error("Failed to launch job for event {}: {}", event.getId(), e.getMessage(), e);
                event.setStatus("FAILED");
                queueService.logEvent(event);
            }
        }
    }
}
