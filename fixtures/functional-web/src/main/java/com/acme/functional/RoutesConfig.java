package com.acme.functional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.accept;

/**
 * The three shapes of the functional API: a builder chain, a predicate plus handler, and a route
 * whose predicate is a parameter — the one that cannot be named, and must be counted as such.
 */
@Configuration
public class RoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> routes(PersonHandler handler) {
        return RouterFunctions.route()
            .GET("/person/{id}", handler::getPerson)
            .POST("/person", accept(MediaType.APPLICATION_JSON), handler::createPerson)
            .DELETE("/person/{id}", handler::deletePerson)
            .build();
    }

    @Bean
    public RouterFunction<ServerResponse> extra(PersonHandler handler) {
        return RouterFunctions.route(GET("/ping"), handler::ping)
            .andRoute(POST("/pong"), handler::pong);
    }

    @Bean
    public RouterFunction<ServerResponse> undecidable(RequestPredicate predicate, PersonHandler handler) {
        return RouterFunctions.route(predicate, handler::handle);
    }
}
