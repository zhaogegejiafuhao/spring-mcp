package com.acme.crud;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Middle of a three-level chain: supplies the URL prefix that {@code AdminController} inherits,
 * plus one handler of its own.
 */
@RequestMapping("/admin")
public abstract class AbstractAdminController extends AbstractCrudController<String, Long> {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
