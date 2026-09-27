<template>
  <div class="page">
    <PageHeader title="项目" subtitle="数据的一级隔离">
      <template #actions>
        <Button :loading="loading" @click="load">刷新</Button>
        <!-- 写入口只在「本进程说了算」的模式下给（见 `canManage`）：multi 的项目真源在
             组织平台，后端对这些写操作也是一律 403，留着按钮等于让人点了才知道。 -->
        <Button v-if="canManage" type="primary" class="bg-[#1677ff]" :disabled="!tenants.length"
                @click="openModal()">新增项目</Button>
      </template>
      <template #help>
        <p>
          项目是数据的一级隔离。血缘、元数据、元数据服务配置都挂在项目上，
          换一个项目看到的就是另一份数据。
        </p>
        <p>
          删除只在其下没有任何业务数据时才允许，默认项目删不掉。
        </p>
        <p>
          这里改的是「有哪些项目」；要<b>进到某个项目里</b>看它的血缘与元数据，
          点那张卡片上的「进入」。右上角的项目切换只换当前项目，不换层级。
        </p>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <span class="muted count">共 {{ allRows.length }} 个项目</span>
    </div>

    <!-- 卡片而不是表格：这一页要回答的是「有哪些项目、我要进哪一个」，
         每行真正会被点的只有「进入」一个动作，摊成四列文字链反而把它埋了。
         方块排开之后「进入」是卡片上唯一的实心按钮，一眼能找到 -->
    <div v-if="allRows.length" class="grid">
      <div v-for="p in allRows" :key="p.id" class="pcard" :class="{ 'pcard-current': isCurrent(p) }">
        <div class="pcard-head">
          <span class="pcard-name" :title="p.name">{{ p.name }}</span>
          <Tag v-if="isCurrent(p)" color="blue">当前</Tag>
          <Tag v-if="p.id === DEFAULT_PROJECT_ID">默认</Tag>
          <Tag class="pcard-status" :color="p.enabled ? 'green' : 'default'">
            {{ p.enabled ? '启用' : '停用' }}
          </Tag>
        </div>

        <div class="pcard-code">{{ p.code }}</div>
        <div class="pcard-desc" :title="p.description || undefined">{{ p.description || '—' }}</div>

        <div class="pcard-foot">
          <div v-if="canManage" class="pcard-links">
            <a @click="openModal(p)">编辑</a>
            <a @click="toggle(p)">{{ p.enabled ? '停用' : '启用' }}</a>
            <Popconfirm
              title="确定删除该项目？仅在其下没有任何业务数据时可删。"
              @confirm="remove(p)"
            >
              <!-- 默认项目后端拒删，这里同步置灰，别让用户点了才知道 -->
              <a class="danger" :class="{ disabled: p.id === DEFAULT_PROJECT_ID }">删除</a>
            </Popconfirm>
          </div>
          <Button type="primary" class="enter-btn" @click="enter(p)">进入</Button>
        </div>
      </div>
    </div>

    <div v-else-if="!loading" class="page-empty">
      <div class="page-empty-text">
        还没有任何项目
      </div>
      <Button v-if="canManage && tenants.length" type="primary" class="bg-[#1677ff]" @click="openModal()">
        新增项目
      </Button>
    </div>

    <Modal v-model:open="modal.open" :title="modal.id ? '编辑项目' : '新增项目'"
           :confirm-loading="saving" @ok="submit">
      <Form layout="vertical" class="mt-3">
        <FormItem label="项目编码" :help="modal.id ? '编码创建后不可修改' : CODE_HELP">
          <Input v-model:value="modal.code" :disabled="!!modal.id" placeholder="如 dw" />
        </FormItem>
        <FormItem label="项目名称">
          <Input v-model:value="modal.name" placeholder="如 离线数仓" />
        </FormItem>
        <FormItem label="描述">
          <Textarea v-model:value="modal.description" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue';
import {
  Button, Form, FormItem, Input, Modal, Popconfirm, Tag, Textarea, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import {
  createProject, deleteProject, listTenants, updateProject,
  type Project, type Tenant,
} from '../../services/api';
import { DEFAULT_PROJECT_ID, switchTo, tenantState } from '../../stores/tenant';
import { LINEAGE_HOME, ownsProjects } from '../../config/pages';

/**
 * 项目管理，工作台那一级。
 *
 * 从原先的「租户与项目」右半栏拆出来。那时项目列表跟着左表的选中行走，
 * 想看某个项目必须先点中它，也没法一眼看到全部项目。
 *
 * <p>这一页是工作台上唯一能「进项目」的地方（概况的对比表里也有一个入口）：
 * 工作台只做项目的增删改与横向对比，要看某个项目自己的血缘与元数据，
 * 得先通过这里的「进入」落到项目层级。
 *
 * 展示用卡片网格而不是表格：竖排的表格里，每行真正的动作只有一个「进入」，
 * 却被「编辑 / 停用 / 删除」三条文链挤在同一格，反而最不显眼。方块排开后
 * 「进入」是每张卡片上唯一的实心按钮 —— 这也正是这一页最常被点的东西。
 * 顺带把描述 ELLIPSIS 掉的那半句放出来了：卡片比表格行宽得多。
 *
 * 数据仍来自 `listTenants()` —— 它把每个租户的 projects 一起带回来了，
 * 不需要为这一页加接口。
 *
 * 本页三种模式都可达（工作台那一级，见 `config/pages.ts` 的 `WORKBENCH_HOME`）。
 * standard 与 standalone 下租户都被收成默认租户一个，所以不再呈现租户筛选与归属列 ——
 * 收紧之前，那个下拉只有一个选项，「所属租户」列每行都是同一个名字。
 * multi 下这一页只读，见 `canManage`。
 *
 * 写操作固定用 `tenantState.tenantId`：普通模式下它已被 `pinDefaultTenant()`
 * 钉成默认租户，与后端强制的租户一致。
 */
const CODE_HELP = '只能用小写字母、数字、下划线和短横线，创建后不可修改';

const tenants = ref<Tenant[]>([]);
const loading = ref(false);
const saving = ref(false);

const modal = reactive({
  open: false,
  id: 0,
  code: '',
  name: '',
  description: '',
});

/** 打平成行：租户列表把各自的项目一起带回来了，这一页只关心项目。 */
const allRows = computed<Project[]>(() => tenants.value.flatMap((t) => t.projects));

/**
 * 能不能在本进程增删改项目。
 *
 * <p>multi 下不能：项目号的真源在组织平台，后端 `assertLocalAdmin()` 对
 * 新建 / 改 / 删一律 403（「多租户模式下项目由组织平台管理」）。所以那一页
 * 退化成纯只读清单 —— 列表照排，「进入」照给，只是不给写入口。
 *
 * <p>「进入」不在收口范围内：它只切本地上下文（`switchTo`），不碰后端。
 */
const canManage = computed(() => ownsProjects());

const isCurrent = (row: Project) =>
  row.id === tenantState.projectId && row.tenantId === tenantState.tenantId;

/**
 * 进入某个项目：切到它，然后落到**项目层级**。
 *
 * <p>目标必须是项目概况 —— 只切 id 不换层级的话，人还站在工作台上，
 * 而工作台看的是全部项目的汇总，点完几乎看不出变化，像没反应。
 * 当前项目也允许「进入」，所以 `switchTo` 那边不能在 id 没变时提前返回。
 */
function enter(record: Project): void {
  switchTo(record.tenantId, record.id, tenantState.tenantName, record.name, LINEAGE_HOME);
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    tenants.value = await listTenants();
  } catch (e: any) {
    message.error('加载项目失败：' + (e?.message || e));
  } finally {
    loading.value = false;
  }
}

function openModal(record?: Project): void {
  modal.open = true;
  modal.id = record?.id ?? 0;
  modal.code = record?.code ?? '';
  modal.name = record?.name ?? '';
  modal.description = record?.description ?? '';
}

async function submit(): Promise<void> {
  if (!modal.code.trim() || !modal.name.trim()) {
    message.error('编码与名称都不能为空');
    return;
  }
  saving.value = true;
  try {
    const payload = {
      code: modal.code.trim(),
      name: modal.name.trim(),
      description: modal.description?.trim() || undefined,
    };
    if (modal.id) {
      const existing = allRows.value.find((p) => p.id === modal.id);
      await updateProject(existing?.tenantId ?? tenantState.tenantId, modal.id,
          { ...payload, status: existing?.status ?? 1 });
    } else {
      await createProject(tenantState.tenantId, payload);
    }
    modal.open = false;
    message.success('已保存');
    await load();
  } catch (e: any) {
    message.error(e?.message || String(e));
  } finally {
    saving.value = false;
  }
}

async function toggle(record: Project): Promise<void> {
  try {
    await updateProject(record.tenantId, record.id, {
      code: record.code,
      name: record.name,
      description: record.description,
      status: record.enabled ? 0 : 1,
    });
    await load();
  } catch (e: any) {
    message.error(e?.message || String(e));
  }
}

async function remove(record: Project): Promise<void> {
  try {
    await deleteProject(record.tenantId, record.id);
    message.success('已删除');
    await load();
  } catch (e: any) {
    // 409 的消息里带着剩余行数与替代方案，值得完整显示，别截断
    message.error({ content: e?.message || String(e), duration: 6 });
  }
}

onMounted(load);
</script>

<style scoped>
.count {
  margin-left: auto;
  font-size: 13px;
}

.mt-3 { margin-top: 12px; }

.grid {
  display: grid;
  /* auto-fill + minmax：窄屏一列、宽屏自动多列，不用写断点 */
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

.pcard {
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.pcard:hover {
  border-color: #d6e4ff;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.06);
}

/* 当前项目加一道蓝边：一眼看出「我现在在哪」，不用去读那个「当前」标签 */
.pcard-current {
  border-color: #91caff;
  background: #fafcff;
}

.pcard-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.pcard-name {
  font-size: 15px;
  font-weight: 500;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pcard-status {
  margin-left: auto;
  flex-shrink: 0;
}

.pcard-code {
  margin-top: 2px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: #9ca3af;
}

.pcard-desc {
  margin-top: 6px;
  /* 描述长短不一，截两行让卡片高度整齐；完整内容在 tooltip 里 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
}

.pcard-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f5f5f5;
}

.pcard-links {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.enter-btn {
  margin-left: auto;
}

.danger { color: #cf1322; }

.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
  pointer-events: none;
}
</style>
