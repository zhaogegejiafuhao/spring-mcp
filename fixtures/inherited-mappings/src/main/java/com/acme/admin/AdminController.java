package com.acme.admin;

import com.acme.crud.AbstractAdminController;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declares no mappings at all: the prefix comes from the grandparent's subclass
 * ({@link AbstractAdminController}), the handlers from both levels above.
 */
@RestController
public class AdminController extends AbstractAdminController {
}
