package com.acme.kotlin.service

import com.acme.kotlin.model.CreateOrder
import com.acme.kotlin.model.OrderView

interface OrderService {

    fun detail(id: Long): OrderView

    fun create(command: CreateOrder): OrderView

    fun delete(id: Long)
}
