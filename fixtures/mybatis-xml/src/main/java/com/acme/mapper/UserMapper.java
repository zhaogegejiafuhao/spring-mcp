package com.acme.mapper;

import com.acme.model.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users")
    List<User> findAll();

    @Insert("INSERT INTO users(name) VALUES(#{name})")
    int insert(@Param("name") String name);

    User findByXml(Long id);
}
