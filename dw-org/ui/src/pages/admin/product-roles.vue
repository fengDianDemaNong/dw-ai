<template>
  <div class="page">
    <PageHeader
      title="产品角色"
      subtitle="每个产品一套角色，决定「谁在这个产品里能做什么」。角色定义是平台级的（跟产品走，不跟租户走）；租户只在项目里把角色派给人。"
    >
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button type="primary" :disabled="!product" @click="openCreate">新增角色</a-button>
      </template>
    </PageHeader>

    <p class="muted card">
      角色<b>不是</b>一张自由勾选的权限表：它的权限词取自各产品<b>自己上报</b>的词表
      （产品前端构建时写进 <code>menu.json</code>，与「<router-link :to="ORG_PAGES.platformNav">菜单管理</router-link>」用的是同一份）。
      改一个角色的权限，立刻影响所有在项目里被派了这个角色的人。
      <br />
      每个产品<b>必须有且只有一个</b>管理角色 —— 租户管理员在该产品里就是按它判权的，所以它删不掉、管理标记也只能「先给别的角色」来转移。
    </p>

    <div class="split">
      <aside class="prod-col">
        <button
          v-for="p in PRODUCT_OPTIONS"
          :key="p.value"
          type="button"
          class="prod"
          :class="{ active: p.value === product }"
          @click="product = p.value"
        >
          <span>{{ p.label }}</span>
          <a-tag :color="rolesOf(p.value).length ? 'blue' : 'default'">{{ rolesOf(p.value).length }}</a-tag>
        </button>
      </aside>

      <section class="role-col">
        <a-table
          :data-source="rolesOf(product)"
          :columns="cols"
          row-key="id"
          size="small"
          :pagination="false"
          class="card card-flush"
          :loading="loading"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'label'">
              <b>{{ record.label }}</b>
              <p v-if="record.hint" class="muted hint">{{ record.hint }}</p>
            </template>
            <template v-else-if="column.key === 'code'">
              <code>{{ record.code }}</code>
            </template>
            <template v-else-if="column.key === 'isAdmin'">
              <a-tag v-if="record.isAdmin" color="gold">管理角色</a-tag>
              <a-tag v-else-if="record.builtin" color="default">内置</a-tag>
            </template>
            <template v-else-if="column.key === 'perms'">
              <!-- 权限词按人话标签显示（标签由产品定义），鼠标悬停能看回原始词 -->
              <template v-if="record.perms.length">
                <a-tag v-for="p in record.perms" :key="p.value" :title="p.value" style="margin-bottom: 2px">
                  {{ p.label || p.value }}
                </a-tag>
              </template>
              <span v-else class="muted">一项都没有 —— 被派到这个角色的人在产品里什么都做不了</span>
            </template>
            <template v-else-if="column.key === 'act'">
              <a-button size="small" @click="openEdit(record)">编辑</a-button>
              <a-button size="small" danger :disabled="record.builtin || record.isAdmin" @click="remove(record)">
                删除
              </a-button>
            </template>
          </template>
        </a-table>

        <p v-if="!rolesOf(product).length && !loading" class="muted card">
          「{{ productLabel(product) }}」还没有任何角色。这个产品的项目里派不了角色，成员进去也没有权限
          —— 用右上角「新增角色」先建一个（建议至少再开一个管理角色）。
        </p>
      </section>
    </div>

    <!-- 编辑抽屉 -->
    <a-drawer
      v-model:open="editOpen"
      :title="form.id ? `编辑角色 · ${form.label || form.code}` : '新增角色'"
      placement="right"
      :width="760"
      :body-style="{ paddingBottom: '80px' }"
    >
      <a-form layout="vertical">
        <a-form-item label="产品" required>
          <a-select v-model:value="form.product" :options="PRODUCT_OPTIONS" :disabled="Boolean(form.id)" />
          <p class="muted" style="margin: 4px 0 0">
            产品 + 角色码一起才是这个角色的身份，建好之后都不能改 —— 改角色码等于「换一个角色」，
            而所有被派了它的人会在一瞬间失去权限。
          </p>
        </a-form-item>
        <a-form-item label="角色名" required>
          <a-input v-model:value="form.label" placeholder="血缘分析" />
        </a-form-item>
        <a-form-item label="角色码" required>
          <!-- `:readonly` 全小写才是 antd-vue 认的 prop（`:read-only` 驼峰化成 readOnly，
               会变成一个打不进去的 dead 属性，字段照样能改）—— 见 nav-items.vue 同处的说明。 -->
          <a-input v-model:value="form.code" :readonly="Boolean(form.id)" placeholder="analyst" />
          <p class="muted" style="margin: 4px 0 0">
            只写成员记录，界面上到处显示的是上面的角色名。小写字母开头，只能用字母、数字、下划线。
          </p>
        </a-form-item>
        <a-form-item label="说明">
          <a-input v-model:value="form.hint" placeholder="解析 SQL、看图；不能改目录规则" />
          <p class="muted" style="margin: 4px 0 0">给派角色的人看的一句话，显示在角色名下面。</p>
        </a-form-item>
        <a-form-item label="排序">
          <a-input-number v-model:value="form.sortOrder" :min="0" style="width: 120px" />
          <p class="muted" style="margin: 4px 0 0">数字小的排在前面（下拉里的顺序）。</p>
        </a-form-item>

        <a-form-item label="管理角色">
          <a-switch v-model:checked="form.isAdmin" :disabled="isCurrentAdmin" />
          <p class="muted" style="margin: 4px 0 0">
            <template v-if="isCurrentAdmin">
              这是「{{ productLabel(form.product) }}」当前的管理角色，不能在这里关掉。
              要让别的角色当管理员，去那个角色上打开这个开关 —— 它会自动降级。
            </template>
            <template v-else>
              打开后，本租户的管理员在这个产品里按<b>这个角色的权限</b>判权（现在那一个是
              「{{ adminRoleLabel }}」，打开时它会自动降级）。
            </template>
          </p>
        </a-form-item>

        <a-form-item label="权限">
          <p class="muted" style="margin: 0 0 8px">
            权限词决定<b>这个人能做产品的哪些事</b>，也决定侧栏里哪些入口对他可见
            （一个菜单挂一个词，能看不能改是产品<b>拆菜单</b>实现的）。
          </p>
          <a-space style="margin-bottom: 8px">
            <a-select
              v-model:value="copyFrom"
              style="width: 240px"
              placeholder="从内置角色复制权限"
              :options="builtinChoices"
              @change="applyCopy"
            />
            <a-button size="small" @click="clearPerms">清空</a-button>
          </a-space>
          <a-checkbox-group
            v-if="permChoices.length"
            :value="[...permSet]"
            :options="permChoices"
            @change="(v: (string | number)[]) => onPermsChange(v)"
          />
          <template v-else>
            <p class="muted" style="margin: 0 0 6px">
              没拿到「{{ productLabel(form.product) }}」的词表（服务没在运行 / 还没构建过前端 /
              「服务注册」里没登记页面地址），暂时只能按原文勾。
            </p>
            <!-- 词表拿不到时至少列出这个角色现有的词，别让它们变成一个看不见也删不掉的集合 -->
            <a-checkbox-group
              v-if="permSet.size"
              :value="[...permSet]"
              :options="[...permSet].map((v) => ({ value: v, label: v }))"
              @change="(v: (string | number)[]) => onPermsChange(v)"
            />
          </template>
        </a-form-item>

        <a-form-item label="按菜单勾选">
          <p class="muted" style="margin: 0 0 8px">
            和上面那份是<b>同一组权限</b>，只是按「这个产品有哪些入口」重新排了一遍：
            勾一个入口 = 给它挂的那个权限词。没挂权限词的菜单不在这里出现 —— 它们对谁都可见。
            <br />
            一个权限词可能挂在好几个菜单上，那时它们会<b>一起</b>亮或一起灭 —— 授权的最小单位是词，不是入口。
          </p>
          <a-spin :spinning="menuLoading">
            <a-tree
              v-if="menuTree.length"
              checkable
              default-expand-all
              :tree-data="menuTree"
              :checked-keys="checkedMenuKeys"
              @check="onTreeCheck"
            />
            <p v-else-if="!menuLoading" class="muted">这个产品还没有配过菜单（见「菜单管理」）。</p>
          </a-spin>
        </a-form-item>
      </a-form>
      <p v-if="noPermMenus.length" class="muted">
        以下菜单没挂权限词，<b>任何人只要进得来就看得见</b>，因此上面勾不出来：
        {{ noPermMenus.join('、') }}。
      </p>

      <template #footer>
        <div class="drawer-foot">
          <span></span>
          <span>
            <a-button @click="editOpen = false">取消</a-button>
            <a-button type="primary" :loading="busy" @click="submit">保存</a-button>
          </span>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { api, type NavItemRow, type PermOption, type ProductRoleRow } from '../../api/client';
import { ORG_PAGES } from '../../config/pages';
import { PRODUCT_OPTIONS, productLabel } from '../../config/products';
import PageHeader from '../../components/PageHeader.vue';

const rows = ref<ProductRoleRow[]>([]);
const loading = ref(false);
const product = ref(PRODUCT_OPTIONS[0]?.value ?? '');

/** 该产品自报的权限词表（「按菜单勾选」与勾选框都要它）。 */
const words = ref<PermOption[]>([]);
/** 已配过的菜单项 —— 菜单树由它派生。 */
const navItems = ref<NavItemRow[]>([]);
const menuLoading = ref(false);

function rolesOf(code: string) {
  return rows.value.filter((r) => r.product === code);
}

async function load() {
  loading.value = true;
  try {
    // 不按产品过滤：左列每个产品都要显示角色数，拉一次全量比 N 次小请求便宜，
    // 也让「切换产品」不会因为要等一次网络往返而闪一下空表。
    rows.value = await api.platform.productRoles();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    loading.value = false;
  }
}

/**
 * 拉当前产品的词表与菜单。
 *
 * <p><b>失败不弹提示</b>：它们是页面的辅助信息（词表拿不到就按原文勾、菜单树拿不到就只用勾选框），
 * 弹窗会盖住真正要报告的保存结果，而这里没有任何需要管理员立刻处理的事。
 */
async function loadContext(code: string) {
  if (!code) return;
  menuLoading.value = true;
  try {
    const [perms, items] = await Promise.all([
      api.platform.productPerms(code).catch(() => ({ product: code, perms: [] as PermOption[] })),
      api.platform.navItems().catch(() => [] as NavItemRow[]),
    ]);
    words.value = perms.perms;
    navItems.value = items.filter((i) => i.product === code);
  } finally {
    menuLoading.value = false;
  }
}

// ---- 编辑 ----
const editOpen = ref(false);
const busy = ref(false);
const copyFrom = ref<string | undefined>(undefined);
const form = reactive({
  id: '',
  product: '',
  code: '',
  label: '',
  hint: '',
  sortOrder: 0,
  isAdmin: false,
});
/** 勾中的权限词。**唯一**的权限状态 —— 勾选框与菜单树都是它的视图。 */
const permSet = ref(new Set<string>());

/** 这个产品当前的管理角色。管理标记只能在别的角色上打开，不能在这里关掉。 */
const adminRole = computed(() => rows.value.find((r) => r.product === form.product && r.isAdmin));
const adminRoleLabel = computed(() => adminRole.value?.label ?? '（当前没有，请尽快设一个）');
const isCurrentAdmin = computed(() => Boolean(form.id) && adminRole.value?.id === form.id);

/**
 * 勾选框的选项 = 产品词表 ∪ 本角色已勾的词。
 *
 * <p>并上后者是给存量数据用的：产品换了词表之后，老角色里的词可能已经不在表里了。
 * 不并的话它在界面上根本不显示，管理员一保存就把这个权限悄悄删掉了。
 */
const permChoices = computed(() => {
  const out: PermOption[] = [...words.value];
  const seen = new Set(out.map((o) => o.value));
  for (const word of permSet.value) {
    if (!seen.has(word)) {
      out.push({ value: word, label: `${word}（产品未上报这个词）` });
      seen.add(word);
    }
  }
  return out;
});

/** 内置角色（可作模板）。只列本产品的、且不是当前正在编辑的这个。 */
const builtinChoices = computed(() =>
  rolesOf(form.product)
    .filter((r) => r.builtin && r.id !== form.id)
    .map((r) => ({ value: r.id, label: `${r.label}（${r.perms.length} 项权限）` })),
);

function applyCopy(id: string) {
  const src = rows.value.find((r) => r.id === id);
  if (!src) return;
  permSet.value = new Set(src.perms.map((p) => p.value));
  message.success(`已复制「${src.label}」的 ${src.perms.length} 项权限，保存后才生效`);
}

function clearPerms() {
  permSet.value = new Set();
}

function onPermsChange(v: (string | number)[]) {
  permSet.value = new Set(v.map(String));
}

// ---- 菜单树 ----
type MenuNode = { key: string; title: string; children?: MenuNode[] };

const SCOPE_LABEL: Record<string, string> = { workbench: '工作台壳', project: '项目壳' };

/**
 * 叶子 key → 它挂的权限词。
 *
 * <p>做成 computed 而不是在 `menuTree` 里顺手填一个 Map：两个 computed 谁先算不确定，
 * 而这里被树与「勾选态」两处读，边算边填会让勾选态读到上一轮的旧映射
 * （表现是切产品后勾选亮错行）。做成独立 computed，两边都从它派生，就没有先后问题。
 */
const permByLeaf = computed(() => {
  const out = new Map<string, string>();
  for (const item of navItems.value) {
    if (item.perm) out.set(`m:${item.id}`, item.perm);
  }
  return out;
});

/**
 * 树 = 壳 → 分组 → 菜单项。**只收挂了权限词的菜单**。
 *
 * <p>没挂词的那些（不判权）不进树：它们对谁都可见，勾不出来、勾了也没有词可落，
 * 放进来只会让人以为「勾上就等于限制了」。它们在抽屉底部用一行文字列出来。
 */
const menuTree = computed<MenuNode[]>(() => {
  const scopes: MenuNode[] = [];
  for (const scope of ['workbench', 'project']) {
    const items = navItems.value.filter((i) => i.scope === scope && i.perm);
    if (!items.length) continue;
    const byGroup = new Map<string, MenuNode[]>();
    for (const item of items) {
      const title = (item.groupTitle || '').trim() || '未分组';
      const leaf: MenuNode = { key: `m:${item.id}`, title: `${item.label}（${permLabel(item.perm)}）` };
      byGroup.set(title, [...(byGroup.get(title) ?? []), leaf]);
    }
    scopes.push({
      key: `s:${scope}`,
      title: SCOPE_LABEL[scope] ?? scope,
      children: [...byGroup.entries()].map(([title, children]) => ({
        key: `g:${scope}/${title}`,
        title,
        children,
      })),
    });
  }
  return scopes;
});

/** 没挂权限词的菜单名（不进树，用一行文字说明）。 */
const noPermMenus = computed(() => navItems.value.filter((i) => !i.perm).map((i) => i.label));

function permLabel(word: string) {
  return words.value.find((o) => o.value === word)?.label || word;
}

/**
 * 树上的勾选态由**权限词集合**推出来，而不是各存一份。
 *
 * <p>只给叶子 key：rc-tree 在非严格模式下会自己把父节点的选中/半选算出来，
 * 所以「工作台壳」这种父节点的对勾不需要我们维护。
 *
 * <p>两个菜单挂同一个词时，勾其中一个等于给了这个词 —— 另一个也会跟着变亮。
 * 这是「权限词才是授权单位」的直接推论，不是 bug，上面那段说明就是讲这个的。
 */
const checkedMenuKeys = computed(() => {
  const keys: string[] = [];
  for (const [key, perm] of permByLeaf.value) {
    if (permSet.value.has(perm)) keys.push(key);
  }
  return keys;
});

/**
 * 树上勾/取消 → 重算权限词集合。
 *
 * <p>不读事件里的「这一次改了哪个节点」，而是拿勾选后的**全量 key 集合**重算：
 * 点父节点会连带一整片、点叶子只影响自己，两种情形用同一条规则就都对，
 * 不必再区分 `node` 到底是不是叶子。参数类型按 `unknown` 收 —— antd 的
 * `check` 事件在严格/非严格模式下签名不同，写死一种会让另一个模式编译不过。
 */
function onTreeCheck(raw: unknown) {
  const checked = new Set((Array.isArray(raw) ? raw : []).map(String));
  const menuPerms = new Set(permByLeaf.value.values());
  const keep = new Set<string>();
  for (const [key, perm] of permByLeaf.value) {
    if (checked.has(key)) keep.add(perm);
  }
  // 不在任何菜单上的词（如 `lineage:write` 只用在 SQL 页的保存按钮）树里没有对应节点，
  // 直接把它们落掉就等于「编辑一次角色就悄悄收回了写权限」，必须原样保留。
  for (const perm of permSet.value) {
    if (!menuPerms.has(perm)) keep.add(perm);
  }
  permSet.value = keep;
}

// ---- 增删 ----
function openCreate() {
  const code = product.value;
  Object.assign(form, {
    id: '',
    product: code,
    code: '',
    label: '',
    hint: '',
    sortOrder: (rolesOf(code).at(-1)?.sortOrder ?? 0) + 10,
    isAdmin: false,
  });
  permSet.value = new Set();
  copyFrom.value = undefined;
  editOpen.value = true;
  void loadContext(code);
}

async function openEdit(row: ProductRoleRow) {
  Object.assign(form, {
    id: row.id,
    product: row.product,
    code: row.code,
    label: row.label,
    hint: row.hint,
    sortOrder: row.sortOrder,
    isAdmin: row.isAdmin,
  });
  permSet.value = new Set(row.perms.map((p) => p.value));
  copyFrom.value = undefined;
  editOpen.value = true;
  await loadContext(row.product);
}

async function submit() {
  if (!form.label.trim() || !form.code.trim()) {
    message.warning('请填写角色名和角色码');
    return;
  }
  busy.value = true;
  const body = {
    label: form.label.trim(),
    hint: form.hint.trim(),
    sortOrder: form.sortOrder ?? 0,
    isAdmin: form.isAdmin,
    perms: [...permSet.value],
  };
  try {
    if (form.id) {
      await api.platform.updateProductRole(form.id, body);
    } else {
      await api.platform.createProductRole({ ...body, product: form.product, code: form.code.trim() });
    }
    editOpen.value = false;
    message.success('已保存');
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function remove(row: ProductRoleRow) {
  try {
    const res = await api.platform.deleteProductRole(row.id);
    await load();
    // 删除不级联清成员的角色列（那等于静默改人的权限），所以要把「还有谁在用」说出来
    if (res.referenced) {
      message.warning(
        `已删除角色「${row.label}」，但还有 ${res.referenced} 条成员记录写着「${res.code}」—— 他们此后在该产品下什么都不做得了`,
      );
    } else {
      message.success(`已删除角色「${row.label}」`);
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

const cols = [
  { title: '角色名', key: 'label', width: 220 },
  { title: '角色码', key: 'code', width: 130 },
  { title: '标记', key: 'isAdmin', width: 96 },
  { title: '权限', key: 'perms' },
  { title: '', key: 'act', width: 150 },
];

onMounted(load);
</script>

<style scoped>
.split {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.prod-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.prod {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  font-size: 13px;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.prod:hover {
  background: rgba(0, 0, 0, 0.03);
}

.prod.active {
  background: rgba(22, 119, 255, 0.08);
  border-color: rgba(22, 119, 255, 0.35);
  font-weight: 600;
}

.hint {
  margin: 2px 0 0;
  font-size: 12px;
}

.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
