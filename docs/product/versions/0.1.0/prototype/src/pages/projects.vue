<template>
  <div class="wrap">
    <header>
      <div class="brand">
        <img src="/logo.svg" alt="" />
        <div>
          <div class="t">智仓 DW-AI</div>
          <div class="s">{{ tenant?.name }} · 租户工作台</div>
        </div>
      </div>
      <div class="right">
        <a-button @click="router.push('/login')">切换租户</a-button>
        <a-button type="primary" @click="open = true">新建项目</a-button>
      </div>
    </header>

    <p class="lead">
      项目是租户下的二级隔离。数仓规范、模型、任务、指标都挂在<strong>租户 + 项目</strong>上。
      进入项目后才能使用具体功能。
    </p>

    <div class="grid">
      <div v-for="p in tenantProjects" :key="p.id" class="card proj">
        <div class="row">
          <h3>{{ p.name }}</h3>
          <a-tag>{{ p.code }}</a-tag>
        </div>
        <p>{{ p.description || '暂无描述' }}</p>
        <div class="meta">Owner {{ p.owner }} · {{ p.createdAt }}</div>
        <a-button type="primary" block @click="go(p.id)">进入项目</a-button>
      </div>
    </div>

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
        <a-form-item label="负责人">
          <a-input v-model:value="form.owner" />
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
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { createProject, currentTenant, enterProject, tenantProjects } from '../stores/app';

const router = useRouter();
const tenant = currentTenant;
const open = ref(false);
const form = reactive({ code: '', name: '', description: '', owner: '张三', bootstrapSpec: true });

function go(id: string) {
  enterProject(id);
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
    owner: form.owner,
    bootstrapSpec: form.bootstrapSpec,
  });
  open.value = false;
  form.code = '';
  form.name = '';
  form.description = '';
  form.bootstrapSpec = true;
  enterProject(p.id);
  router.push('/w');
}
</script>

<style scoped>
.wrap {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px 48px;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.brand {
  display: flex;
  gap: 10px;
  align-items: center;
}

.brand img {
  width: 36px;
  height: 36px;
}

.t {
  font-weight: 700;
}

.s,
.lead,
.meta,
p {
  color: #64748b;
  font-size: 13px;
}

.lead {
  margin-bottom: 20px;
}

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
}

.meta {
  margin-bottom: 12px;
}
.hint {
  margin-top: 6px;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.5;
}
</style>
