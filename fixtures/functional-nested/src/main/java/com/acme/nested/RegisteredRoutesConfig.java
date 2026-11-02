package com.acme.nested;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * halo's shape: the nesting lives here, the routes it wraps are built somewhere else.
 *
 * <p>
 * {@code CustomEndpointsBuilder} does exactly this — {@code nest(path("/apis/" + gv), () ->
 * customEndpoint.endpoint())}. The prefix is real and applies to every route of every endpoint, but
 * no lexical walk can attach it to them, so the only honest output is a warning that the layer exists.
 * </p>
 */
@Configuration
public class RegisteredRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> registered(List<RouterFunction<ServerResponse>> routers) {
        return RouterFunctions.route()
            .nest(RequestPredicates.path("/apis/registered/v1"), () -> routers.stream()
                .reduce(RouterFunction::and)
                .orElse(null))
            .build();
    }
}
