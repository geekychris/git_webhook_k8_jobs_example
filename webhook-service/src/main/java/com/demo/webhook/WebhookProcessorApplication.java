package com.demo.webhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebhookProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebhookProcessorApplication.class, args);
    }
}
