package com.demo.webhook.client;

import com.demo.webhook.model.WebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class SidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String enrichmentUrl;
    private final String auditUrl;

    public SidecarClient(
            ObjectMapper objectMapper,
            @Value("${sidecar.enrichment.url:http://localhost:5000}") String enrichmentUrl,
            @Value("${sidecar.audit.url:http://localhost:5001}") String auditUrl) {
        this.objectMapper = objectMapper;
        this.enrichmentUrl = enrichmentUrl;
        this.auditUrl = auditUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> enrich(WebhookEvent event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(enrichmentUrl + "/enrich"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                log.info("Enrichment result for event {}: {}", event.getId(), result);
                return result;
            }
            log.warn("Enrichment sidecar returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Enrichment sidecar unavailable: {}", e.getMessage());
        }
        return Map.of();
    }

    public void audit(String action, Map<String, Object> details) {
        try {
            Map<String, Object> payload = Map.of("action", action, "details", details, "timestamp", java.time.Instant.now().toString());
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(auditUrl + "/audit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Audit sidecar returned status {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Audit sidecar unavailable: {}", e.getMessage());
        }
    }
}
