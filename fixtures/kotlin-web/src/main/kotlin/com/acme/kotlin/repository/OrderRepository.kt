package com.acme.kotlin.repository

import org.springframework.data.jpa.repository.JpaRepository

import com.acme.kotlin.model.Order

interface OrderRepository : JpaRepository<Order, Long> {

    fun findByStatus(status: Map<String, List<String>>): List<Order>
}
