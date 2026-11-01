package com.acme.functional

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

@Configuration
class KotlinRoutesConfig {

    @Bean
    fun routes(handler: PersonHandler): RouterFunction<ServerResponse> = RouterFunctions.route()
        .GET("/kt/person/{id}", handler::getPerson)
        .POST("/kt/person", handler::createPerson)
        .build()

    @Bean
    fun extra(handler: PersonHandler): RouterFunction<ServerResponse> =
        RouterFunctions.route(GET("/kt/ping"), handler::ping)
}
