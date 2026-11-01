package com.acme.kotlin.service;

import org.springframework.stereotype.Service;

import com.acme.kotlin.model.CreateOrder;
import com.acme.kotlin.model.OrderView;

/**
 * The Java half of the same interface: one project, two languages, one answer.
 */
@Service
public class OrderAuditService implements OrderService {

    @Override
    public OrderView detail(Long id) {
        return new OrderView(id);
    }

    @Override
    public OrderView create(CreateOrder command) {
        return new OrderView(1L);
    }

    @Override
    public void delete(Long id) {
        // no-op
    }
}
