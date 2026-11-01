package com.acme.lombok;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shape Lombok users write: {@code final} fields plus {@code @RequiredArgsConstructor}, so the
 * constructor that Spring injects through does not exist in the source at all.
 *
 * <p>
 * A call-site analyzer that resolved receivers from constructors would see nothing here. This one
 * resolves from field declarations, which Lombok does not remove — so the field is the whole reason
 * this fixture can pass.
 * </p>
 */
@RestController
@RequestMapping("/lombok")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/orders")
    public String create(String body) {
        return orderService.place(body);
    }
}
