package com.acme.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConst.BASE)
public class AdminController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
