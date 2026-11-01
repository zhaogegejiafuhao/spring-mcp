package com.acme.job;

import com.acme.order.OrderService;

public class BadJob {

    public void run() {
        // Chained receiver: the type of getOrderService() is not determinable syntactically.
        getOrderService().create("bad");
    }

    private OrderService getOrderService() {
        return null;
    }
}
