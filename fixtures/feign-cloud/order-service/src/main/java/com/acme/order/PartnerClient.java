package com.acme.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A client to a service outside this repository: it has a URL, so no module can be resolved.
 */
@FeignClient(name = "partner", url = "https://partner.example.com")
public interface PartnerClient {

    @RequestMapping("/ping")
    String ping();
}
