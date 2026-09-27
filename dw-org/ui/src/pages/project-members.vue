<template>
  <div class="page">
    <PageHeader
      title="成员管理"
      subtitle="角色在本项目生效，且按产品分别设置。同一个产品在不同项目里可以是不同角色。"
    >
      <template #actions>
        <a-tag v-if="myRole">{{ labelOf(myRole) }}</a-tag>
        <a-button v-if="canAdd" type="primary" @click="openAdd">添加成员</a-button>
      </template>
    </PageHeader>

    <a-alert v-if="loadError" type="error" show-icon :message="loadError" class="pad" />
    <a-alert
      v-else-if="canManage && !isRealTenantAdmin"
      type="info"
      show-icon
      class="pad"
      message="你有本项目的成员管理权限"
      description="但候选用户名单只对本组织管理员开放，所以不能在这里添加人。改角色与移出不受影响。"
    />

    <a-table
      :data-source="rows"
      :columns="cols"
      row-key="userId"
      size="small"
      :loading="loading"
      :pagination="false"
      class="card card-flush"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.displayName || record.userId }}</template>
        <template v-else-if="column.key === 'account'">{{ record.username || record.userId }}</template>
        <template v-else-if="column.key === 'role'">
          <span v-for="p in products" :key="p" class="role-cell">
            <span class="mem-product">{{ productLabel(p) }}</span>
            <a-select
              :value="record.byProduct[p]"
              :options="roleOpts"
              placeholder="未加入"
              style="width: 132px"
              :disabled="!canManage"
              @change="(v: unknown) => changeRole(record, p, String(v))"
            />
          </span>
        </template>
        <template v-else-if="column.key === 'ops'">
          <a-tooltip :title="removeHint(record)">
            <span>
              <a-popconfirm
                v-if="canRemove(record)"
                title="移出后他在本项目的所有产品角色都会被清掉（不是只摘掉某一个产品）。"
                ok-text="移出"
                cancel-text="取消"
                @confirm="remove(record)"
              >
                <a-button type="link" danger size="small">移出</a-button>
              </a-popconfirm>
              <a-button v-else type="link" danger size="small" disabled>移出</a-button>
            </span>
          </a-tooltip>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="addOpen"
      title="添加成员"
      ok-text="加入"
      :confirm-loading="adding"
      @ok="submitAdd"
    >
      <a-alert v-if="candidatesError" type="warning" show-icon :message="candidatesError" class="mb" />
      <a-form layout="vertical">
        <a-form-item label="用户">
          <a-select
            v-model:value="addUser"
            show-search
            option-filter-prop="label"
            :options="candidateOpts"
            placeholder="从本组织用户中选择"
            style="width: 100%"
            @search="(v: string) => (candidateQuery = v)"
          />
        </a-form-item>
        <a-form-item label="产品">
          <a-select v-model:value="addProduct" :options="productOpts" style="width: 100%" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="addRole" :options="roleOpts" style="width: 100%" />
        </a-form-item>
      </a-form>
      <p class="hint">
        同一个人在每个产品下各有一个角色 —— 要让他再参与一个产品，加入后再执行一次。
      </p>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目壳的「成员管理」页（`/org/project/{项目码}/members`）。
 *
 * <h2>这一页与「产品菜单」的关系</h2>
 *
 * <p>侧栏里其余的项目页面都是各服务报上来的（`menu.json` → 菜单管理 → 挂在项目壳），
 * 只有这一页是<b>壳自己的功能</b> —— 所以它由 `config/sysNav.ts` 的固定项渲染，
 * 不依赖任何服务是否登记了页面。
 *
 * <h2>三个刻意的取舍</h2>
 *
 * <ol>
 *   <li><b>按地址里的项目码解析项目，不读 sessionStorage 的「当前项目」</b>。
 *       后者是「上次去过哪儿」的记忆，与地址可以不一致（粘贴地址、前进/后退进来）。
 *       判权与取数一律以地址为准（见 `stores/app.ts` 的 `projectByCode`）。</li>
 *   <li><b>角色变更不做乐观更新</b>：`await` 远端成功后再 `reload()`，失败弹后端原话。
 *       本地先改会让「页面显示已改、库里没改」这种故障没有任何痕迹。</li>
 *   <li><b>一人一行、行内每个产品一个下拉</b>：后端的数据粒度是
 *       (项目, 用户, 产品)，而下游的 `project_members` 里同一个人可能只有其中一个产品的行。
 *       只做一个产品的下拉会造出「下拉里改了角色、另一个产品没变」的假开关。</li>
 * </ol>
 *
 * <h2>这一页做不到的事（不假装能做）</h2>
 *
 * <ul>
 *   <li><b>摘掉单个产品的角色</b>：后端只有整人删除（`DELETE .../members/{uid}` 清掉
 *       该人在本项目<b>所有产品</b>的行），没有产品维的删除。所以下拉不提供「清空」，
 *       未加入的产品显示占位「未加入」，一旦选过就只能改不能退 —— 要退只能整人移出。</li>
 *   <li><b>非管理员的项目管理员加人</b>：候选名单来自 `api.org.users`，而
 *       `TenantAdminService.listUsers` 第一行就是 `requireTenantAdmin()`。所以
 *       「添加成员」按钮的条件是 `canManage && isRealTenantAdmin`，达不到时给一条
 *       说明而不是一个永远为空的下拉。</li>
 * </ul>
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { message } from 'ant-design-vue';
import { api, type OrgUser } from '../api/client';
import PageHeader from '../components/PageHeader.vue';
import { ROLE_LABEL } from '../config/iam';
import { productLabel } from '../config/products';
import {
  app,
  bootstrapRemote,
  can,
  hasModule,
  isRealTenantAdmin,
  projectByCode,
  projectRoleOf,
} from '../stores/app';
import type { ProjectMember } from '../types';

const route = useRoute();

/** 表格的一行 = 一个人（后端给的是人 × 产品，这里按人聚合，产品落进 `byProduct`）。 */
type Row = {
  userId: string;
  displayName: string;
  username: string;
  byProduct: Record<string, string>;
};

const code = computed(() => String(route.params.code ?? ''));
const project = computed(() => projectByCode(code.value));

const members = ref<ProjectMember[]>([]);
const loading = ref(false);
const loadError = ref('');

const canManage = computed(() =>
  project.value ? can('warehouse', 'iam:member', project.value.id) : false
);
/**
 * 能不能在本项目拉候选人。两个条件缺一不可：本项目的成员管理权限（后端
 * `requireMember`）+ 组织管理员身份（候选名单端点 `requireTenantAdmin`）。
 */
const canAdd = computed(() => canManage.value && isRealTenantAdmin.value);

const myRole = computed(() =>
  project.value ? projectRoleOf('warehouse', project.value.id) : undefined
);

const rows = computed<Row[]>(() => {
  const byUser = new Map<string, Row>();
  for (const m of members.value) {
    let row = byUser.get(m.userId);
    if (!row) {
      row = {
        userId: m.userId,
        displayName: m.displayName ?? '',
        username: m.username ?? '',
        byProduct: {},
      };
      byUser.set(m.userId, row);
    }
    // 缺 product 的行按仓建设算 —— 与 `stores/app.ts` 的 projectRoleOf 同一口径。
    row.byProduct[m.product ?? 'warehouse'] = m.role;
  }
  return [...byUser.values()].sort((a, b) => a.userId.localeCompare(b.userId));
});

/**
 * 表格要展示的产品列。
 *
 * <p>「本租户开通的产品」∪「成员行里出现过的产品」：只取前者会漏掉历史数据
 * （租户后来关掉了某个产品，那些行还在，却看不到、改不了）；只取后者则在一个成员
 * 都没有时列是空的，看不出这个项目支持哪些产品。
 */
const products = computed(() => {
  const out: string[] = [];
  for (const p of ['warehouse', 'metadata'] as const) if (hasModule(p)) out.push(p);
  for (const m of members.value) {
    const p = m.product ?? 'warehouse';
    if (!out.includes(p)) out.push(p);
  }
  return out;
});

const productOpts = computed(() => products.value.map((p) => ({ value: p, label: productLabel(p) })));

/**
 * 角色选项。初值是内置三档，`reload` 时用后端的角色表覆盖。
 *
 * <p>初值必须有：它是**兜底**而不是占位 —— 三档内置角色在任何环境里都存在。
 * 拿不到角色表时下拉仍然可用（只是新建的自定义角色显示为原始码而不是中文名），
 * 所以这里不为失败弹错：一个能用的下拉配一个不完整的名字，比一个空下拉好。
 */
const roleOpts = ref<{ value: string; label: string }[]>(
  Object.entries(ROLE_LABEL).map(([value, label]) => ({ value, label }))
);

function labelOf(role: string): string {
  return roleOpts.value.find((o) => o.value === role)?.label ?? ROLE_LABEL[role as never] ?? role;
}

const cols = [
  { title: '显示名', key: 'name' },
  { title: '用户', key: 'account' },
  { title: '角色', key: 'role' },
  { title: '操作', key: 'ops', width: 90 },
];

async function reload() {
  const p = project.value;
  if (!p) {
    loadError.value = `找不到项目「${code.value}」—— 地址里的项目码可能已改过或项目已删除。`;
    members.value = [];
    return;
  }
  loading.value = true;
  try {
    members.value = await api.listMembers(p.id);
    loadError.value = '';
  } catch (e) {
    // 403 也走这里：把后端原话显示出来（通常就是「未加入该项目」），而不是留一张空表
    // 让人以为这个项目没有成员。
    loadError.value = errText(e);
    members.value = [];
  } finally {
    loading.value = false;
  }
  try {
    const roles = await api.projectMemberRoles(p.id);
    if (roles.length) {
      roleOpts.value = roles.map((r) => ({ value: r.code, label: r.label }));
    }
  } catch {
    // 保留内置三档兜底，理由见 roleOpts 的说明
  }
}

async function changeRole(row: Row, product: string, role: string) {
  const p = project.value;
  if (!p || !role || row.byProduct[product] === role) return;
  try {
    await api.putMember(p.id, row.userId, role, product);
    await reload();
    message.success(`${row.displayName || row.userId} 在${productLabel(product)}下现为${labelOf(role)}`);
  } catch (e) {
    message.error(errText(e));
    // 失败后也重读一次：错误可能是「请求到了、响应丢了」，此时远端其实已经改了。
    // 不重读的话页面会一直显示旧值，而库里是新值 —— 下一次刷新才「莫名其妙」变了。
    await reload();
  }
}

function removeHint(row: Row): string {
  if (!canManage.value) return '需要本项目的成员管理权限';
  const p = project.value;
  if (p && row.userId === p.owner) return '项目创建人不能被移出，请先转让项目';
  if (row.userId && row.userId === app.currentUserId) return '不能把自己移出项目';
  return '';
}

function canRemove(row: Row): boolean {
  const p = project.value;
  if (!canManage.value || !p) return false;
  return row.userId !== p.owner && row.userId !== app.currentUserId;
}

async function remove(row: Row) {
  const p = project.value;
  if (!p) return;
  try {
    await api.deleteMember(p.id, row.userId);
    await reload();
    message.success(`已把 ${row.displayName || row.userId} 移出本项目`);
  } catch (e) {
    message.error(errText(e));
    await reload();
  }
}

// ---------------------------------------------------------------------------
// 添加成员
// ---------------------------------------------------------------------------

const addOpen = ref(false);
const adding = ref(false);
const addUser = ref<string>();
const addProduct = ref<string>();
const addRole = ref<string>();
const candidateQuery = ref('');
const candidates = ref<OrgUser[]>([]);
const candidatesError = ref('');

/**
 * 候选人下拉。
 *
 * <p>不过滤「已在项目里的人」：同一个人的**不同产品**仍然是可加的目标（他可能只在
 * 仓建设里有角色）。按 userId 去重会把这些正常的加人操作挡掉。
 */
const candidateOpts = computed(() =>
  candidates.value
    .filter((u) => u.status === 'active')
    .map((u) => ({ value: u.id, label: `${u.displayName || u.id}（${u.username || u.id}）` }))
);

async function openAdd() {
  addOpen.value = true;
  addUser.value = undefined;
  candidateQuery.value = '';
  addProduct.value = products.value[0];
  addRole.value = roleOpts.value[0]?.value;
  candidatesError.value = '';
  const tid = app.currentTenantId;
  if (!tid) {
    candidates.value = [];
    candidatesError.value = '未选定租户，拉不到用户名单。';
    return;
  }
  try {
    candidates.value = await api.org.users(tid);
  } catch (e) {
    candidates.value = [];
    candidatesError.value = `拉不到本组织用户：${errText(e)}`;
  }
}

async function submitAdd() {
  const p = project.value;
  if (!p) return;
  if (!addUser.value || !addProduct.value || !addRole.value) {
    message.warning('请选择用户、产品与角色');
    return;
  }
  adding.value = true;
  try {
    await api.putMember(p.id, addUser.value, addRole.value, addProduct.value);
    addOpen.value = false;
    await reload();
    message.success('已加入');
  } catch (e) {
    message.error(errText(e));
  } finally {
    adding.value = false;
  }
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

onMounted(async () => {
  // 直接刷新这一页时项目列表可能还没拉过（`bootstrapRemote` 是异步的），
  // 不补这一次的话 `projectByCode` 会解析失败，页面错误地报「找不到项目」。
  if (!app.projects.length) await bootstrapRemote();
  await reload();
});
</script>

<style scoped>
.pad {
  margin: 12px 16px;
}

.mb {
  margin-bottom: 12px;
}

.role-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 16px;
}

.mem-product {
  color: var(--text-2, #666);
  font-size: 12px;
}

.hint {
  color: var(--text-2, #666);
  font-size: 12px;
  margin: 0;
}
</style>
