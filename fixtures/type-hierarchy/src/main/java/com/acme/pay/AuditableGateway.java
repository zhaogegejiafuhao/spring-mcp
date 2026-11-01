package com.acme.pay;

/** A sub-interface: skipped by default, reported when includeInterfaces=true. */
public interface AuditableGateway extends PaymentGateway {

    String auditId();
}
