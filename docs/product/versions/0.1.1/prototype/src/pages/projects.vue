<template>
  <div class="page">
    <PageHeader
      :title="multi ? '项目管理' : '项目'"
      :subtitle="
        multi
          ? '租户管理员新建项目并指定项目管理员；项目管理员再进项目拉人、派角色。未加入的项目不会出现在普通成员列表里。'
          : '管理员新建项目并指定项目管理员。普通模式没有租户和平台后台。'
      "
    >
      <template #actions>
        <a-button v-if="isTenantAdmin" type="primary" @click="openCreate">新建项目</a-button>
      </template>
    </PageHeader>

    <div class="grid">
      <div v-for="p in tenantProjects" :key="p.id" class="card proj">
        <div class="row">
          <h3>{{ p.name }}</h3>
          <a-tag>{{ p.code }}</a-tag>
        </div>
        <p>{{ p.description || '暂无描述' }}</p>
        <div class="meta">项目管理员 {{ p.owner }} · {{ p.createdAt }}</div>
        <a-button type="primary" block @click="go(p.id)">进入项目</a-button>
      </div>
    </div>
    <p v-if="!tenantProjects.length" class="muted">还没有可进入的项目。</p>

    <a-modal v-model:open="open" title="新建项目" ok-text="创建" @ok="create">
      <a-form layout="vertical">
        <a-form-item label="项目编码" required>
          <a-input v-model:value="form.code" placeholder="trade_dw" />
        </a-form-item>
        <a-form-item label="项目名称" required>
          <a-input v-model:value="form.name" placeholder="交易数仓" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="form.description" :rows="3" />
        </a-form-item>
        <a-form-item label="项目管理员" required>
          <a-select
            v-model:value="form.adminUserId"
            placeholder="从本租户用户中指定"
            :options="adminOpts"
          />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="form.bootstrapSpec">
            导入通用规范模板（分层 + 四级等级 + 基础词根）
          </a-checkbox>
          <div class="hint">空项目建议勾选，之后可在规范中心再改。不勾选则只有技术/时间词根。</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import PageHeader from '../components/PageHeader.vue';
import { isMultiTenant } from '../config/runtime';
import { createProject, enterProject, isTenantAdmin, sessionAccount, tenantProjects, tenantUsers } from '../stores/app';

const router = useRouter();
const multi = isMultiTenant();
const open = ref(false);
const form = reactive({
  code: '',
  name: '',
  description: '',
  adminUserId: sessionAccount.value?.id ?? '',
  bootstrapSpec: true,
});
const adminOpts = computed(() =>
  tenantUsers()
    .filter((u) => u.status === 'active')
    .map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))
);

function openCreate() {
  form.adminUserId = sessionAccount.value?.id ?? adminOpts.value[0]?.value ?? '';
  open.value = true;
}

function go(id: string) {
  if (!enterProject(id)) return;
  router.push('/w');
}

function create() {
  if (!form.code || !form.name) {
    message.warning('请填写编码和名称');
    return;
  }
  const p = createProject({
    code: form.code,
    name: form.name,
    description: form.description,
    adminUserId: form.adminUserId,
    bootstrapSpec: form.bootstrapSpec,
  });
  if (!p) return;
  open.value = false;
  form.code = '';
  form.name = '';
  form.description = '';
  form.bootstrapSpec = true;
}
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.proj h3 {
  margin: 0;
  font-size: 16px;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.proj p {
  min-height: 40px;
  margin: 8px 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.meta,
.hint {
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
}
</style>
