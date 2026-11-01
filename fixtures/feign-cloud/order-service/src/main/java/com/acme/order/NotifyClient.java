package com.acme.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Outbound: this service calls notify-service. The gateways' routes point the other way.
 */
@FeignClient(name = "notify-service", path = "/api/notify")
public interface NotifyClient {

    @PostMapping("/send")
    String send(@RequestBody String message);

    @GetMapping("/status/{id}")
    String status(@PathVariable String id);
}
