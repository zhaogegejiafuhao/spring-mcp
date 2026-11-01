package com.acme.pet;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Takes its class-level prefix and one handler from {@link PetApi}, and adds one of its own.
 * The prefix applies to the added handler too, which is why {@code count()} maps to /pet/count.
 */
@RestController
public class PetController implements PetApi {

    @Override
    public String get(String id) {
        return id;
    }

    @GetMapping("/count")
    public int count() {
        return 0;
    }
}
