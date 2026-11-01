package com.acme.lombok;

import lombok.Data;

/** Lombok on a data holder: only the annotation and the fields exist in source. */
@Data
public class OrderDto {

    private Long id;

    private String code;
}
