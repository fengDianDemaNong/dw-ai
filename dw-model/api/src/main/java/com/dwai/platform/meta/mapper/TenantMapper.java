package com.dwai.platform.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwai.platform.meta.entity.TenantEntity;
import org.apache.ibatis.annotations.Select;

public interface TenantMapper extends BaseMapper<TenantEntity> {
  @Select("SELECT * FROM tenants WHERE code = #{code}")
  TenantEntity selectByCode(String code);
}
