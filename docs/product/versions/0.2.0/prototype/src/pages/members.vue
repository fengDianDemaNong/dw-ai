<template>
  <div class="page">
    <PageHeader
      title="项目成员"
      subtitle="组织角色只有项目管理员和成员。工种角色按本组织已启用的产品分别派，不会出现「给元数据派一个建模工程师」。"
    >
      <template #actions>
        <a-tag>{{ currentRoleLabel }}</a-tag>
        <a-button v-if="canAdd" type="primary" @click="open = true">添加成员</a-button>
      </template>
    </PageHeader>

    <p class="lead">
      项目管理员在各已启用产品上自动拥有该产品的管理角色。成员只派他要用的产品。租户「模块管理」还可以按权限隐藏模块：未派且不在可见范围内的人，侧栏根本看不到该项。
    </p>

    <a-table :data-source="rows" :columns="cols" row-key="userId" size="small" :pagination="false" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'org'">
          <a-select
            :value="record.role === 'admin' ? 'admin' : 'member'"
            :options="orgOpts"
            style="width: 140px"
            :disabled="!canAdd"
            @change="(v: unknown) => setOrgProjectKind(record.userId, v as 'admin' | 'member')"
          />
        </template>
        <template v-else-if="column.key?.startsWith('p:')">
          <template v-if="record.role === 'admin'">
            <span class="auto">{{ adminMapped(column.key.slice(2)) }}</span>
          </template>
          <a-select
            v-else
            :value="productRoleOf(record.userId, column.key.slice(2) as ProductModule) || undefined"
            :options="[{ value: '', label: '未派' }, ...productRoleOptions(column.key.slice(2) as ProductModule)]"
            style="width: 160px"
            :disabled="!canAdd"
            allow-clear
            placeholder="未派"
            @change="(v: unknown) => setProductRole(record.userId, column.key.slice(2) as ProductModule, (v as string) || undefined)"
          />
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" title="添加成员" ok-text="加入" :ok-button-props="{ disabled: !pick }" @ok="add">
      <a-form layout="vertical">
        <a-form-item label="本租户用户">
          <a-select
            v-model:value="pick"
            placeholder="选择本租户用户"
            :options="available.map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))"
          />
        </a-form-item>
        <a-form-item label="组织角色">
          <a-select v-model:value="kind" :options="orgOpts" />
        </a-form-item>
        <template v-if="kind === 'member'">
          <a-form-item v-for="p in productCols" :key="p.code" :label="p.label">
            <a-select v-model:value="draftRoles[p.code]" :options="[{ value: '', label: '未派' }, ...productRoleOptions(p.code)]" />
          </a-form-item>
        </template>
        <p v-else class="muted">项目管理员自动映射各已开通产品的管理角色。</p>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../components/PageHeader.vue';
import { ORG_PROJECT_ROLE_OPTS } from '../config/iam';
import { PRODUCT_MANIFESTS, productOf, productRoleLabel, productRoleOptions } from '../config/products';
import type { ProductModule } from '../types';
import {
  currentProjectRole,
  isProjectAdmin,
  openedProducts,
  productRoleOf,
  projectMemberList,
  setMemberRole,
  setOrgProjectKind,
  setProductRole,
  tenantUsers,
} from '../stores/app';

const rows = projectMemberList;
const open = ref(false);
const pick = ref<string>();
const kind = ref<'admin' | 'member'>('member');
const draftRoles = reactive<Record<string, string>>({});
const orgOpts = ORG_PROJECT_ROLE_OPTS;

const productCols = computed(() =>
  PRODUCT_MANIFESTS.filter((p) => openedProducts().includes(p.code))
);

const canAdd = computed(() => isProjectAdmin());
const currentRoleLabel = computed(() => {
  const role = currentProjectRole();
  if (!role) return '未加入项目';
  if (role === 'admin') return '项目管理员';
  return '项目成员';
});
const available = computed(() =>
  tenantUsers().filter((u) => u.status === 'active' && !rows.value.some((m) => m.userId === u.id))
);

const cols = computed(() => [
  { title: '用户', dataIndex: 'displayName' },
  { title: '用户名', dataIndex: 'username', width: 100 },
  { title: '组织', key: 'org', width: 160 },
  ...productCols.value.map((p) => ({
    title: p.label,
    key: `p:${p.code}`,
    width: 180,
  })),
]);

function adminMapped(code: string) {
  return `${productRoleLabel(code as ProductModule, productOf(code as ProductModule).adminRole)}（自动）`;
}

function add() {
  if (!pick.value) {
    message.warning('请选择本租户用户');
    return;
  }
  if (kind.value === 'admin') {
    setMemberRole(pick.value, 'admin');
  } else {
    setOrgProjectKind(pick.value, 'member');
    for (const p of productCols.value) {
      const code = draftRoles[p.code];
      if (code) setProductRole(pick.value, p.code, code);
    }
  }
  pick.value = undefined;
  kind.value = 'member';
  open.value = false;
}
</script>

<style scoped>
.lead,
.muted {
  color: var(--muted);
  font-size: 13px;
  margin: 0 0 12px;
}
.auto {
  color: var(--muted);
  font-size: 12px;
}
</style>
