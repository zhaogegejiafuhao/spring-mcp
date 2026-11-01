package com.acme.order;

import org.springframework.cloud.openfeign.FeignClient;

/** Declared but with no mapped method: the contract is missing, and that is worth a warning. */
@FeignClient("no-methods")
public interface EmptyClient {
}
