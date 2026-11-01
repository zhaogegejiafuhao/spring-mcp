package com.acme.chain;

import org.springframework.stereotype.Service;

/** Level two: calls the leaf and is called by the controller. */
@Service
public class MiddleService {

    private final LeafService leafService;

    public MiddleService(LeafService leafService) {
        this.leafService = leafService;
    }

    public String handle(String input) {
        return leafService.work(input);
    }
}
