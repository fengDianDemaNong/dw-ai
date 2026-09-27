# dw-common（共享 DTO / 公共契约）

> 由 `bin/gen-index.sh` 于 2026-09-27 18:15:33 生成（HEAD `04d51fd`）。**不要手工编辑**，改完代码重跑脚本即可。
> 共 36 个类。**路径 = 源码根 `dw-common/src/main/java/` + 下表路径**；分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。


## meta/dto

- `com/dwai/platform/meta/dto/ApiModels.java` — ApiModels

## meta/entity

- `com/dwai/platform/meta/entity/AppearancePrefEntity.java` — AppearancePrefEntity [实体]
- `com/dwai/platform/meta/entity/PlatformAccessEntity.java` — PlatformAccessEntity [实体]
- `com/dwai/platform/meta/entity/ProjectEntity.java` — ProjectEntity [实体]
- `com/dwai/platform/meta/entity/ProjectMemberEntity.java` — ProjectMemberEntity [实体] 项目成员的产品角色。
- `com/dwai/platform/meta/entity/RefreshTokenEntity.java` — RefreshTokenEntity [实体]
- `com/dwai/platform/meta/entity/TenantAiPromptEntity.java` — TenantAiPromptEntity [实体]
- `com/dwai/platform/meta/entity/TenantEntity.java` — TenantEntity [实体]
- `com/dwai/platform/meta/entity/TenantGrantEntity.java` — TenantGrantEntity [实体]
- `com/dwai/platform/meta/entity/TenantKnowledgeArticleEntity.java` — TenantKnowledgeArticleEntity [实体]
- `com/dwai/platform/meta/entity/TenantLicenseEntity.java` — TenantLicenseEntity [实体]
- `com/dwai/platform/meta/entity/TenantLlmEntity.java` — TenantLlmEntity [实体]
- `com/dwai/platform/meta/entity/UserEntity.java` — UserEntity [实体]
- `com/dwai/platform/meta/entity/UserTenantEntity.java` — UserTenantEntity [实体]

## meta/mapper

- `com/dwai/platform/meta/mapper/AppearancePrefMapper.java` — AppearancePrefMapper [数据访问]
- `com/dwai/platform/meta/mapper/PlatformAccessMapper.java` — PlatformAccessMapper [数据访问]
- `com/dwai/platform/meta/mapper/ProjectMapper.java` — ProjectMapper [数据访问]
- `com/dwai/platform/meta/mapper/ProjectMemberMapper.java` — ProjectMemberMapper [数据访问]
- `com/dwai/platform/meta/mapper/RefreshTokenMapper.java` — RefreshTokenMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantAiPromptMapper.java` — TenantAiPromptMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantGrantMapper.java` — TenantGrantMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantKnowledgeArticleMapper.java` — TenantKnowledgeArticleMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantLicenseMapper.java` — TenantLicenseMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantLlmMapper.java` — TenantLlmMapper [数据访问]
- `com/dwai/platform/meta/mapper/TenantMapper.java` — TenantMapper [数据访问]
- `com/dwai/platform/meta/mapper/UserMapper.java` — UserMapper [数据访问]
- `com/dwai/platform/meta/mapper/UserTenantMapper.java` — UserTenantMapper [数据访问]

## meta/seed

- `com/dwai/platform/meta/seed/SeedMain.java` — SeedMain 手动灌演示数据。 · `main`

## meta/support

- `com/dwai/platform/meta/support/AiCaps.java` — AiCaps
- `com/dwai/platform/meta/support/AiPromptDefaults.java` — AiPromptDefaults
- `com/dwai/platform/meta/support/DbVendors.java` — DbVendors 方言判定，供共享层使用。
- `com/dwai/platform/meta/support/JsonbStringTypeHandler.java` — JsonbStringTypeHandler
- `com/dwai/platform/meta/support/Jsons.java` — Jsons
- `com/dwai/platform/meta/support/Perms.java` — Perms
- `com/dwai/platform/meta/support/RunModes.java` — RunModes 运行模式的回落链。
- `com/dwai/platform/meta/support/SeedDb.java` — SeedDb 演示数据 seed 的库型判定与脚本定位。

