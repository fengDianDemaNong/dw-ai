package com.dwai.platform.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwai.platform.meta.entity.UserEntity;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<UserEntity> {
  @Select("SELECT * FROM users WHERE username = #{username}")
  UserEntity selectByUsername(String username);
}
