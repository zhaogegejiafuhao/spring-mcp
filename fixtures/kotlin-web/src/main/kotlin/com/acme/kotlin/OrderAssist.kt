package com.acme.kotlin

import com.acme.kotlin.service.OrderService
import com.acme.kotlin.service.OrderServiceImpl

/**
 * Receivers that are not fields: the two rules the Kotlin scanner has to earn its keep on.
 */
class OrderAssist {

    fun viaParam(target: OrderService, id: Long): String = target.detail(id).id.toString()

    fun viaLocal(id: Long): String {
        val local = OrderServiceImpl()
        return local.detail(id).id.toString()
    }
}
