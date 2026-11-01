package com.acme.pay;

import org.springframework.stereotype.Service;

/** Concrete, but reaches the interface through the abstract base: direct=false. */
@Service
public class CardGateway extends AbstractGateway {

    @Override
    public String charge(String amount) {
        return "card:" + validate(amount);
    }
}
