package com.acme.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes that live in no YAML file: the whole point of reading the DSL.
 */
@Configuration
public class CodeRoutes {

    @Bean
    public RouteLocator codeRoutes(RouteLocatorBuilder builder) {
        // A literal in a local variable is followed; anything else stays reported as written.
        String external = "https://example.com";
        return builder.routes()
            .route("code-orders", r -> r.path("/code/orders/**")
                .filters(f -> f.stripPrefix(2))
                .uri("lb://order-service"))
            .route("code-external", r -> r.host("**.example.com")
                .uri(external))
            .build();
    }
}
