package com.acme.kotlin

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

import com.acme.kotlin.model.CreateOrder
import com.acme.kotlin.model.OrderView
import com.acme.kotlin.service.OrderService

@RestController
@RequestMapping("/orders")
class OrderController(
    private val service: OrderService,
    private val helper: OrderHelper
) {

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): OrderView = service.detail(id)

    @PostMapping
    fun create(@RequestBody command: CreateOrder): OrderView {
        helper.record("create")
        return service.create(command)
    }

    @RequestMapping(method = [RequestMethod.PUT], path = ["/bulk"])
    fun bulkUpdate(): String = "ok"

    @DeleteMapping("/{id}")
    fun remove(@PathVariable id: Long) {
        service.delete(id)
    }

    @GetMapping(ApiPaths.BASE + "/summary")
    fun summary(): String = "unresolved"

    @GetMapping("/echo/$id")
    fun echo(@PathVariable id: Long): String = id.toString()
}
