package com.acme.kotlin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class PingController : AbstractCrudController<PingController>() {

    @GetMapping("/pong")
    fun pong(): String = "pong"
}
