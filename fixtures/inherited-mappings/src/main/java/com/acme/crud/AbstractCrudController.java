package com.acme.crud;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Typical enterprise CRUD base: the URLs live here, the entity type comes from the subclass.
 * No class-level mapping — each subclass supplies its own prefix.
 */
public abstract class AbstractCrudController<T, ID> {

    @GetMapping
    public String list() {
        return "list";
    }

    @GetMapping("/{id}")
    public String get(@PathVariable ID id) {
        return "get";
    }

    @PostMapping
    public String create(@RequestBody T body) {
        return "create";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable ID id) {
        return "delete";
    }
}
