package com.acme.pay;

/** Three concrete implementations reach it: two directly, one through an abstract base. */
public interface PaymentGateway {

    String charge(String amount);
}
