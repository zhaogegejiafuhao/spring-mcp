package com.acme.config;

import org.mybatis.spring.annotation.MapperScan;

@MapperScan("com.acme.wild.**.mapper")
public class WildcardScanConfig {
}
