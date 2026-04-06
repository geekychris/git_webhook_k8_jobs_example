package com.demo.webhook.service;

import com.demo.webhook.model.JobRecord;
import com.demo.webhook.model.WebhookEvent;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;

@Service
public class JobLauncherService {

    private static final Logger log = LoggerFactory.getLogger(JobLauncherService.class);

    private KubernetesClient kubeClient;
    private final QueueService queueService;

    @Value("${kubernetes.namespace:webhook-demo}")
    private String namespace;

    @Value("${job.image:job-processor:latest}")
    private String jobImage;

    @Value("${spring.data.redis.host:redis}")
    private String redisHost;

    public JobLauncherService(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostConstruct
    public void init() {
        this.kubeClient = new KubernetesClientBuilder().build();
        log.info("Kubernetes client initialized for namespace: {}", namespace);
    }

    @PreDestroy
    public void destroy() {
        if (kubeClient != null) {
            kubeClient.close();
        }
    }

    public String launchJob(WebhookEvent event) {
        String jobName = String.format("pr-review-%s-%d-%s",
                event.getRepository().replaceAll("[^a-z0-9]", "").toLowerCase(),
                event.getPrNumber(),
                event.getId());

        // Truncate to K8s name limit
        if (jobName.length() > 63) {
            jobName = jobName.substring(0, 63);
        }

        log.info("Launching K8s job: {} for PR #{} in {}", jobName, event.getPrNumber(), event.getRepository());

        Job job = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .withLabels(Map.of(
                            "app", "pr-review-job",
                            "pr-number", String.valueOf(event.getPrNumber()),
                            "event-id", event.getId()
                    ))
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(2)
                    .withTtlSecondsAfterFinished(300)
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewToleration()
                                .withKey("node.kubernetes.io/disk-pressure")
                                .withOperator("Exists")
                                .withEffect("NoSchedule")
                            .endToleration()
                            .addNewContainer()
                                .withName("pr-reviewer")
                                .withImage(jobImage)
                                .withImagePullPolicy("IfNotPresent")
                                .addNewEnv().withName("JOB_ID").withValue(event.getId()).endEnv()
                                .addNewEnv().withName("JOB_NAME").withValue(jobName).endEnv()
                                .addNewEnv().withName("PR_NUMBER").withValue(String.valueOf(event.getPrNumber())).endEnv()
                                .addNewEnv().withName("PR_TITLE").withValue(event.getPrTitle() != null ? event.getPrTitle() : "").endEnv()
                                .addNewEnv().withName("REPOSITORY").withValue(event.getRepository()).endEnv()
                                .addNewEnv().withName("HEAD_SHA").withValue(event.getHeadSha() != null ? event.getHeadSha() : "abc123").endEnv()
                                .addNewEnv().withName("ACTION").withValue(event.getAction()).endEnv()
                                .addNewEnv().withName("REDIS_HOST").withValue(redisHost).endEnv()
                                .addNewEnv().withName("REDIS_PORT").withValue("6379").endEnv()
                                .withNewResources()
                                    .addToRequests("cpu", new Quantity("100m"))
                                    .addToRequests("memory", new Quantity("128Mi"))
                                    .addToLimits("cpu", new Quantity("500m"))
                                    .addToLimits("memory", new Quantity("256Mi"))
                                .endResources()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        kubeClient.batch().v1().jobs().inNamespace(namespace).resource(job).create();

        // Track in Redis
        JobRecord record = new JobRecord();
        record.setJobId(event.getId());
        record.setEventId(event.getId());
        record.setJobName(jobName);
        record.setRepository(event.getRepository());
        record.setPrNumber(event.getPrNumber());
        record.setStatus("RUNNING");
        queueService.trackActiveJob(event.getId(), record);

        log.info("Job created: {}", jobName);
        return jobName;
    }

    public void checkJobStatuses() {
        var activeJobs = queueService.getActiveJobs();
        for (JobRecord record : activeJobs) {
            try {
                Job job = kubeClient.batch().v1().jobs()
                        .inNamespace(namespace)
                        .withName(record.getJobName())
                        .get();

                if (job == null) {
                    queueService.completeJob(record.getJobId(), "FAILED", "Job not found");
                    continue;
                }

                var status = job.getStatus();
                if (status == null) continue;

                if (status.getSucceeded() != null && status.getSucceeded() > 0) {
                    log.info("Job completed successfully: {}", record.getJobName());
                    // Try to get result from Redis (job writes it there)
                    queueService.completeJob(record.getJobId(), "SUCCEEDED", "PR review completed");
                } else if (status.getFailed() != null && status.getFailed() > 0) {
                    log.warn("Job failed: {}", record.getJobName());
                    queueService.completeJob(record.getJobId(), "FAILED", "Job execution failed");
                }
            } catch (Exception e) {
                log.warn("Error checking job status for {}: {}", record.getJobName(), e.getMessage());
            }
        }
    }
}
