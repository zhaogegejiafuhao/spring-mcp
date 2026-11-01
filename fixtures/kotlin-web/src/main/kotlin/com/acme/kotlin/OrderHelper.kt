package com.acme.kotlin

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class OrderHelper {

    @Autowired
    lateinit var clock: OrderClock

    private val cache: MutableMap<String, String> = mutableMapOf()

    fun record(action: String) {
        cache[action] = action
        clock.now()
    }

    companion object {
        const val PREFIX = "/orders"

        fun label(): String = PREFIX
    }
}
