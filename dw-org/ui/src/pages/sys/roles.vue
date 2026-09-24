<template>
  <div class="page">
    <PageHeader
      title="角色管理"
      subtitle="权限跟角色走，不能任意勾选功能。下面是本产品固定角色，以及当前组织里谁在用。"
    />

    <div class="grid">
      <article v-for="r in catalog" :key="r.key" class="card role">
        <div class="who">{{ r.scope }}</div>
        <h3>{{ r.label }}</h3>
        <p>{{ r.desc }}</p>
      </article>
    </div>

    <div class="card mt">
      <h3>本{{ multi ? '租户' : '组织' }}人员</h3>
      <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'tenant'">
            {{ TENANT_ROLE_LABEL[(record.tenantRole || 'member') as 'admin' | 'member'] }}
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type OrgUser } from '../../api/client';
import PageHeader from '../../components/PageHeader.vue';
import { PROJECT_ROLE_LABEL, TENANT_ROLE_LABEL } from '../../config/iam';
import { isMultiTenant } from '../../config/runtime';
import { app } from '../../stores/app';

const multi = isMultiTenant();
const users = ref<OrgUser[]>([]);

const catalog = [
  ...(multi
    ? [
        { key: 't-admin', scope: '租户', label: '租户管理员', desc: '新建项目、管本租户用户、指定项目管理员、生成授权码、转让管理员。' },
        { key: 't-member', scope: '租户', label: '普通成员', desc: '只能进被派进的项目。不能开账号、不能开项目。' },
      ]
    : [{ key: 'org-admin', scope: '组织', label: '管理员', desc: '新建项目、管用户、指定项目管理员。普通模式没有租户。' }]),
  { key: 'p-admin', scope: '项目', label: '项目管理员', desc: '拉项目成员、改规范、建模与发布。' },
  { key: 'p-modeler', scope: '项目', label: '建模工程师', desc: '规范只读，建模中心可作业。' },
  { key: 'p-viewer', scope: '项目', label: '只读访客', desc: '规范与建模都只能看。' },
];

const rows = computed(() =>
  users.value.map((u) => {
    const projects = app.projects
      .filter((p) => p.tenantId === app.currentTenantId)
      .map((p) => {
        // 加产品过滤：产品维进来后同一个人在同一项目下会有多行（仓建设、数据地图各一行），
        // 不指定产品会随机取一行、显示成另一个产品的角色。
        // 这一页的角色词表（见上面的 catalog）整张都是仓建设语义 —— 「拉项目成员、改规范、
        // 建模与发布」，所以这里明确取仓建设那行。数据地图的角色（目录管理员 / 血缘分析 /
        // 只读访客）这一页不展示。
        const role = app.members.find(
          (m) =>
            m.projectId === p.id && m.userId === u.id && (m.product ?? 'warehouse') === 'warehouse'
        )?.role;
        return role ? `${p.name} · ${PROJECT_ROLE_LABEL[role]}` : null;
      })
      .filter(Boolean)
      .join('；');
    return {
      id: u.id,
      displayName: u.displayName,
      username: u.username,
      tenantRole: u.tenantRole || 'member',
      projects: projects || '未派进项目',
    };
  })
);

const cols = [
  { title: '显示名', dataIndex: 'displayName' },
  { title: '用户名', dataIndex: 'username', width: 120 },
  ...(multi ? [{ title: '租户角色', key: 'tenant', width: 140 }] : []),
  { title: '项目角色', dataIndex: 'projects' },
];

onMounted(async () => {
  if (app.currentTenantId) users.value = await api.org.users(app.currentTenantId);
});
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
