package com.acme.pet;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Mapping annotations on an interface: Spring reads them from the interface when the implementation
 * does not repeat them, so the endpoint exists at runtime even though the controller never declares
 * it.
 */
@RequestMapping("/pet")
public interface PetApi {

    @GetMapping("/{id}")
    String get(String id);
}
