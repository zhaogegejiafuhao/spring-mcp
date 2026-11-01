package com.acme.job;

import com.acme.order.OrderService;
import java.util.List;

public class LambdaJob {

    public void runAll(List<OrderService> services) {
        // Lambda parameter type needs inference; deliberately not guessed.
        services.forEach(s -> s.create("lambda"));
    }
}
