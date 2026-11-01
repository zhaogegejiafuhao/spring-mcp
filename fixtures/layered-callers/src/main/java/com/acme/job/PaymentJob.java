package com.acme.job;

import com.acme.order.OrderService;

public class PaymentJob {

    public void run(OrderService svc) {
        svc.create("job");
    }
}
