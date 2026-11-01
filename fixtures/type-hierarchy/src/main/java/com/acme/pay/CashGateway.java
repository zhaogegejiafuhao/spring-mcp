package com.acme.pay;

import org.springframework.stereotype.Component;

/** Concrete and direct, and not named like the interface: grep for the type name would miss it. */
@Component
public class CashGateway implements PaymentGateway {

    @Override
    public String charge(String amount) {
        return "cash:" + amount;
    }
}
