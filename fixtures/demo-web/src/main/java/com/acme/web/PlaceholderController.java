package com.acme.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}")
public class PlaceholderController {

    @GetMapping("/status")
    public String status() {
        return "ok";
    }
}
