package com.acme.kotlin.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper

import com.acme.kotlin.model.Order

@org.apache.ibatis.annotations.Mapper
interface OrderMapper : BaseMapper<Order> {

    fun countByStatus(status: String): Long
}
