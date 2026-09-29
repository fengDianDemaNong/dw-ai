package com.dwai.platform.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwai.platform.meta.entity.NavEntryProductLinkEntity;

/**
 * 只走 wrapper 查询的 mapper —— 本表主键是复合的，{@code selectById} 语义不对。
 * 理由见 {@link NavEntryProductLinkEntity} 的类注释。
 */
public interface NavEntryProductLinkMapper extends BaseMapper<NavEntryProductLinkEntity> {}
