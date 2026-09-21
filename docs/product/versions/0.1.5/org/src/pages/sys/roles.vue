<template>
  <div class="page">
    <PageHeader
      title="角色管理"
      subtitle="组织角色很少且稳定。工种角色由各独立产品自己定义，组合时按产品派，不写死「建模工程师」套到所有模块。"
    />

    <div class="grid">
      <article v-for="r in catalog" :key="r.key" class="card role">
        <div class="who">{{ r.scope }}</div>
        <h3>{{ r.label }}</h3>
        <p>{{ r.desc }}</p>
      </article>
    </div>

    <div class="card mt">
      <h3>本租户人员</h3>
      <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'tenant'">
            {{ TENANT_ROLE_LABEL[record.tenantRole as TenantOrgRole] }}
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import { PRODUCT_MANIFESTS, productRoleLabel } from '../../config/products';
import { TENANT_ROLE_LABEL } from '../../config/iam';
import { isMultiTenant } from '../../config/runtime';
import { app, openedProducts, tenantRoleOf, tenantUsers } from '../../stores/app';
import type { TenantOrgRole } from '../../types';

const opened = openedProducts();

const catalog = [
  ...(isMultiTenant()
    ? [
        { key: 't-admin', scope: '租户', label: '租户管理员', desc: '新建项目、管本租户用户、指定项目管理员、生成授权码、转让管理员、裁本组织模块与可见范围。' },
        { key: 't-member', scope: '租户', label: '普通成员', desc: '只能进被派进的项目。不能开账号、不能开项目。' },
      ]
    : [{ key: 'org-admin', scope: '组织', label: '管理员', desc: '新建项目、管用户、指定项目管理员。普通模式没有租户。' }]),
  { key: 'p-admin', scope: '组织', label: '项目管理员', desc: '拉人、按产品派角。各已开通产品自动映射为该产品的管理角色。' },
  { key: 'p-member', scope: '组织', label: '项目成员', desc: '只在被派了角色的产品里做事。未派的产品菜单禁用。' },
  ...PRODUCT_MANIFESTS.filter((p) => opened.includes(p.code)).flatMap((p) =>
    p.roles.map((r) => ({
      key: `${p.code}-${r.code}`,
      scope: p.label,
      label: r.label,
      desc: r.hint,
    }))
  ),
];

const rows = computed(() =>
  tenantUsers().map((u) => {
    const projects = app.projects
      .filter((p) => p.tenantId === app.currentTenantId)
      .map((p) => {
        const mem = app.members.find((m) => m.projectId === p.id && m.userId === u.id);
        if (!mem) return null;
        if (mem.role === 'admin') return `${p.name} · 项目管理员`;
        const bits = opened
          .map((code) => {
            const r = mem.productRoles?.[code] || (code === 'warehouse' ? mem.role : undefined);
            return r && r !== 'admin' ? `${productRoleLabel(code, r)}` : null;
          })
          .filter(Boolean);
        return `${p.name} · 成员${bits.length ? `（${bits.join(' / ')}）` : ''}`;
      })
      .filter(Boolean)
      .join('；');
    return {
      id: u.id,
      displayName: u.displayName,
      username: u.username,
      tenantRole: (tenantRoleOf(u.id) ?? 'member') as TenantOrgRole,
      projects: projects || '未派进项目',
    };
  })
);

const cols = [
  { title: '显示名', dataIndex: 'displayName' },
  { title: '用户名', dataIndex: 'username', width: 120 },
  ...(isMultiTenant() ? [{ title: '租户角色', key: 'tenant', width: 140 }] : []),
  { title: '项目角色', dataIndex: 'projects' },
];
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.role .who {
  font-size: 11px;
  color: var(--muted);
  letter-spacing: 0.08em;
}

.role h3 {
  margin: 6px 0 8px;
  font-size: 15px;
}

.role p,
.card > h3 {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

.card > h3 {
  margin-bottom: 12px;
  color: inherit;
}

.mt {
  margin-top: 16px;
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
