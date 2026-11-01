package com.acme.chain;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Level three: only calls the middle service, never the leaf directly. */
@RestController
public class TopController {

    private final MiddleService middleService;

    public TopController(MiddleService middleService) {
        this.middleService = middleService;
    }

    @GetMapping("/top")
    public String top() {
        return middleService.handle("x");
    }
}
