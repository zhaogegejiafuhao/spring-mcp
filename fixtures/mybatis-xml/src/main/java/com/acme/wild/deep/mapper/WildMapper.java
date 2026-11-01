package com.acme.wild.deep.mapper;

/**
 * Covered only by a wildcard scan pattern, the way RuoYi declares its mappers:
 * {@code @MapperScan("com.acme.wild.**.mapper")}. Prefix matching never matches that string.
 */
public interface WildMapper {

    String selectWild(Long id);
}
