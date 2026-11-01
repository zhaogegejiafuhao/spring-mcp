package com.acme.plus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Generic base: its parameter is a type variable, not an entity, and must not be reported as one. */
public interface MyBaseMapper<T> extends BaseMapper<T> {

    int countAll();
}
