package com.acme.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CleanupJob {

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        // Deliberately does not touch OrderService, so caller counts stay unchanged.
    }
}
