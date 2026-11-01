package com.acme.billing;

import com.acme.ordering.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillingController {

    private final OrderService orderService;

    public BillingController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/billing")
    public String preview() {
        return orderService.create("preview");
    }
}
