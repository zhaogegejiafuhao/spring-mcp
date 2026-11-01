package com.acme.report;

import com.acme.order.OrderService;
import com.acme.order.OrderServiceImpl;

public class ReportService {

    public void run() {
        OrderService local = new OrderServiceImpl();
        local.create("report");
    }
}
