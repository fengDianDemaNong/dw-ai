package com.dwai.platform.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwai.platform.meta.entity.ProductRolePermEntity;

/**
 * 只走 wrapper 查询的 mapper —— 本表主键是复合的，{@code selectById} 语义不对。
 * 理由见 {@link ProductRolePermEntity} 的类注释。
 */
public interface ProductRolePermMapper extends BaseMapper<ProductRolePermEntity> {}
