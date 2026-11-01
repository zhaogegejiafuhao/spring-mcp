package com.acme.owner;

import com.acme.crud.AbstractCrudController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inherits four handlers from the base class and overrides one of them.
 *
 * <p>
 * The override re-declares its mapping, so it must appear once (not twice), with the subclass as the
 * declaring type. The other three must still be reported even though they are not written here.
 * </p>
 */
@RestController
@RequestMapping("/owners")
public class OwnerController extends AbstractCrudController<String, Long> {

    @Override
    @GetMapping("/search")
    public String list() {
        return "search";
    }
}
