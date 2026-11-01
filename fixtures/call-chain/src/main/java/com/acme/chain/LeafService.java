package com.acme.chain;

import org.springframework.stereotype.Service;

/** Bottom of a three-level chain: the type whose change we want to assess. */
@Service
public class LeafService {

    public String work(String input) {
        return "leaf:" + input;
    }
}
