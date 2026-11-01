package com.acme.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public String list() {
        return "list";
    }

    @GetMapping("/{id}")
    public String get(@PathVariable String id) {
        return id;
    }

    @PostMapping
    public String create(@RequestBody String body) {
        return body;
    }

    @RequestMapping(value = "/legacy", method = { RequestMethod.GET, RequestMethod.POST })
    public String legacy() {
        return "legacy";
    }
}
