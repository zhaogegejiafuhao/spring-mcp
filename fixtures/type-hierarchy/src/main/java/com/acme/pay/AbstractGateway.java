package com.acme.pay;

/** Abstract base: implements the interface, cannot be a bean, and is skipped unless asked for. */
public abstract class AbstractGateway implements PaymentGateway {

    protected final String validate(String amount) {
        return amount == null ? "0" : amount.trim();
    }
}
