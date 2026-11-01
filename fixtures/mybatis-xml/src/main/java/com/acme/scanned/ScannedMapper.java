package com.acme.scanned;

/**
 * Plain interface with no annotation and no MyBatis supertype: only {@code @MapperScan} on the config
 * class below marks it as a mapper.
 */
public interface ScannedMapper {

    String ping();
}
