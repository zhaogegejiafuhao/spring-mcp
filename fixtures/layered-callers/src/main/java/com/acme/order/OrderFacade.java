package com.acme.order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderFacade {

    @Autowired
    private OrderService orderService;

    public String place(String body) {
        return orderService.create(body);
    }
}
