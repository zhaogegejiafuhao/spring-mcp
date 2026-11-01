package com.acme.kotlin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * A base controller with a class-level prefix and one handler, both of which a subclass inherits.
 * Nested /* block comments */ must not confuse the lexer.
 */
@RequestMapping("/api")
abstract class AbstractCrudController<T> {

    @GetMapping("/ping")
    fun ping(): String = "pong"
}
