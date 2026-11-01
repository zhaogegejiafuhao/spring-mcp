package com.acme.deep.mapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper of a nested module: its XML sits nine levels below the repository root, which is where the
 * old discovery cap of 8 stopped looking. mall has 76 mappers in exactly that position.
 */
@Mapper
public interface DeepMapper {

    String selectDeep(Long id);
}
