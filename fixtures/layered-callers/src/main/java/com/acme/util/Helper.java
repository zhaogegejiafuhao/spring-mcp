package com.acme.util;

import com.acme.order.OrderService;

public final class Helper {

    private Helper() {
    }

    public static void run(OrderService s) {
        s.create("helper");
    }
}
