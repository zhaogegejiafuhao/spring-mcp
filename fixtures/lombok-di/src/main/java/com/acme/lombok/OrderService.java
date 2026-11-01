package com.acme.lombok;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    public String place(String body) {
        return "placed:" + body;
    }
}
