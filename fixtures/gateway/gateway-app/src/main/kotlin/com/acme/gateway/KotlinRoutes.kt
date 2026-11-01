package com.acme.gateway

import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KotlinRoutes {

    @Bean
    fun kotlinRoutes(builder: RouteLocatorBuilder): RouteLocator = builder.routes {
        route("kt-notify") {
            path("/kt/notify/**")
            filters { stripPrefix(1) }
            uri("lb://notify-service")
        }
    }
}
