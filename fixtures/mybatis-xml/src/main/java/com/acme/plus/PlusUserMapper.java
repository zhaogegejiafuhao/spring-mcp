package com.acme.plus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus style: no {@code @Mapper}, and no XML either. Extending {@code BaseMapper<T>} is the
 * entire declaration, so a detector that only looks for annotations misses it.
 */
public interface PlusUserMapper extends BaseMapper<PlusUser> {

    PlusUser selectByCode(String code);
}
