package com.acme.kotlin.service

import org.springframework.stereotype.Service

import com.acme.kotlin.model.CreateOrder
import com.acme.kotlin.model.OrderView

@Service
class OrderServiceImpl : OrderService {

    override fun detail(id: Long): OrderView = OrderView(id)

    override fun create(command: CreateOrder): OrderView = OrderView(1L)

    override fun delete(id: Long) {
        // no-op
    }
}
