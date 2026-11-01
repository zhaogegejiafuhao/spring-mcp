package com.acme.order;

import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl implements OrderService {

    @Override
    public String create(String body) {
        return "created:" + body;
    }
}
