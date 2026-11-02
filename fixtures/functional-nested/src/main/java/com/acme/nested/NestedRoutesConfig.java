package com.acme.nested;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The three nesting shapes that decide whether a reported URL is callable at all.
 *
 * <p>
 * Taken from halo: {@code CustomEndpointsBuilder} nests every custom endpoint under
 * {@code "/apis/" + groupVersion}, so the paths written inside each endpoint supplier are never the
 * URLs a client calls. The {@code /health} route here is deliberately a sibling of the nest — it must
 * not inherit the prefix.
 * </p>
 */
@Configuration
public class NestedRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> console(ConsoleHandler handler) {
        return RouterFunctions.route()
            .nest(RequestPredicates.path("/apis/console/v1alpha1"), () -> RouterFunctions.route()
                .GET("/posts", handler::listPosts)
                .POST("/posts", handler::createPost)
                .build())
            .GET("/health", handler::health)
            .build();
    }

    @Bean
    public RouterFunction<ServerResponse> dynamic(ConsoleHandler handler, String groupVersion) {
        return RouterFunctions.route()
            .nest(RequestPredicates.path("/apis/" + groupVersion), () -> RouterFunctions.route()
                .GET("/tags", handler::listTags)
                .build())
            .build();
    }
}
