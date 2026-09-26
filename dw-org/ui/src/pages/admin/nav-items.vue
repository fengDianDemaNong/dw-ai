<template>
  <div class="page">
    <PageHeader
      title="菜单管理"
      subtitle="把各产品的页面挂到两个壳上：工作台（进项目之前）与项目（进项目之后）。路径来自各服务自己报的候选清单，管理员决定它挂哪个壳、排第几。"
    >
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button @click="openGroups">分组管理</a-button>
        <a-button :disabled="!canCreate" @click="openFetch">从服务拉取菜单</a-button>
        <a-button type="primary" :disabled="!canCreate" @click="openCreate">新增菜单</a-button>
      </template>
    </PageHeader>

    <p v-if="!configuredProducts.length" class="muted card">
      还没有登记任何产品的页面地址，先到
      <router-link :to="ORG_PAGES.platformServices">服务注册</router-link>
      填上要嵌入的产品，再回来配菜单。
    </p>

    <div class="filters">
      <a-radio-group v-model:value="scopeFilter" button-style="solid">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button value="workbench">工作台壳</a-radio-button>
        <a-radio-button value="project">项目壳</a-radio-button>
      </a-radio-group>
    </div>

    <a-table
      :data-source="treeRows"
      :columns="cols"
      row-key="id"
      :pagination="false"
      size="small"
      class="card card-flush"
      :indent-size="14"
      :row-class-name="rowClass"
      :expanded-row-keys="expandedKeys"
      @expand="onExpand"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a-tag v-if="record.depth === 0" :color="record.scope === 'project' ? 'blue' : 'purple'">
            {{ record.name }}
          </a-tag>
          <span v-else :class="{ 'node-branch': record.nodeType === 'branch' }">{{ record.name }}</span>
          <!-- 空组：已登记分组但这一壳这一产品下一条菜单都没有。不标出来，管理员会以为没保存成功 -->
          <a-tag v-if="record.emptyGroup" color="default" style="margin-left: 6px">空</a-tag>
          <!-- 未登记：这个分组名只存在于菜单项里（各服务动态生成的，如仓建设的「建模中心」） -->
          <a-tag v-else-if="record.unregisteredGroup" style="margin-left: 6px">未登记</a-tag>
        </template>
        <template v-else-if="column.key === 'icon'">
          <template v-if="record.nodeType === 'item'">
            <component :is="icons[record.icon]" v-if="icons[record.icon]" />
            <span v-else class="muted">通用</span>
          </template>
        </template>
        <template v-else-if="column.key === 'sortOrder'">
          <span v-if="record.nodeType === 'item'">{{ record.sortOrder }}</span>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-tag v-if="record.nodeType === 'item'" :color="record.enabled ? 'green' : 'default'">
            {{ record.enabled ? '启用' : '停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <template v-if="record.nodeType === 'item'">
            <a-button size="small" @click="openEdit(record.item!)">编辑</a-button>
            <a-button size="small" danger @click="remove(record.id)">删除</a-button>
          </template>
        </template>
      </template>
    </a-table>
    <p v-if="rows.length" class="muted">
      菜单项里的路径是<b>子应用内</b>的路径（如 <code>/lineage/tables</code>），不是本平台的地址 ——
      本平台会把它拼成 <code>/org/embed/{产品}{路径}</code>（项目壳下多一级 <code>/org/project/{项目}{产品}{路径}</code>）。
    </p>

    <!-- 从服务拉取候选 -->
    <a-drawer
      v-model:open="fetchOpen"
      title="从服务拉取菜单"
      placement="right"
      :width="1120"
      :body-style="{ paddingBottom: '80px' }"
    >
      <p class="muted">
        候选来自各服务前端构建时导出的 <code>menu.json</code>，后端按「服务注册」里登记的
        <b>页面地址</b>去拉。菜单变更要重新构建该服务的前端才会进到这里。
      </p>

      <a-spin :spinning="fetching">
        <a-alert
          v-if="candidates.length && !fetchableCount"
          type="warning"
          show-icon
          message="没有拉到任何候选"
          description="下面每个产品的失败原因里带了实际请求的地址，可以先照着核对「服务注册」里填的页面地址。"
          style="margin-bottom: 12px"
        />

        <!-- 左树右选：左边是服务上报的菜单树（产品 → 壳·分组 → 菜单项），右边是即将导入的清单。
             勾一个分组 = 整组导入。 -->
        <div class="pick-split">
          <div class="pick-left">
            <div v-for="group in failedCandidates" :key="group.product" class="cand-fail">
              <div class="cand-head">
                <b>{{ productLabel(group.product) }}</b>
                <a-tag color="red">拉取失败</a-tag>
              </div>
              <p v-if="group.url" class="muted cand-url">请求地址：<code>{{ group.url }}</code></p>
              <a-alert type="error" show-icon :message="group.error" style="margin-bottom: 8px" />
            </div>

            <a-tree
              v-if="candTree.length"
              checkable
              :selectable="false"
              default-expand-all
              :tree-data="candTree"
              :checked-keys="checkedKeys"
              @check="onTreeCheck"
            />
            <p v-else-if="!fetching && !failedCandidates.length" class="muted">没有可导入的候选。</p>
          </div>

          <div class="pick-right">
            <div class="pick-head">
              <b>将导入 {{ pickedRows.length }} 项</b>
              <span v-if="pickedRows.length" class="muted">归属壳、分组、菜单名、权限词、排序都能改了再保存</span>
            </div>

            <a-table
              v-if="pickedRows.length"
              :data-source="pickedRows"
              :columns="pickedCols"
              row-key="id"
              size="small"
              :pagination="false"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'scope'">
                  <a-select
                    v-model:value="draft[record.id].scope"
                    size="small"
                    style="width: 108px"
                    :options="scopeChoices"
                  />
                </template>
                <template v-else-if="column.key === 'group'">
                  <a-auto-complete
                    v-model:value="draft[record.id].group"
                    size="small"
                    placeholder="不分组"
                    :options="groupOptions(draft[record.id].scope, record.product)"
                  />
                </template>
                <template v-else-if="column.key === 'label'">
                  <a-input v-model:value="draft[record.id].label" size="small" />
                </template>
                <template v-else-if="column.key === 'perm'">
                  <!-- 与手填表单用的是同一个控件：两处各写一遍必然会漂成「同一件事能填的词不一样」 -->
                  <PermSelect
                    v-model:value="draft[record.id].perm"
                    :options="optionsOf(record.product)"
                    placeholder="不判权"
                  />
                </template>
                <template v-else-if="column.key === 'sort'">
                  <a-input-number v-model:value="draft[record.id].sortOrder" size="small" :min="0" style="width: 72px" />
                </template>
                <template v-else-if="column.key === 'act'">
                  <a-button size="small" @click="unpick(record)">移除</a-button>
                </template>
              </template>
            </a-table>

            <p v-else class="muted empty-pick">
              在左边勾选要挂上来的菜单。勾<b>一个分组</b>（主菜单）会把这一组下面的菜单一起选进来 ——
              像数仓建模的「建模中心」，它下面的分层是按项目动态生成的，逐条勾太碎。
            </p>
          </div>
        </div>
      </a-spin>

      <template #footer>
        <div class="drawer-foot">
          <span class="muted">已选 {{ picked.size }} 项</span>
          <span>
            <a-button @click="fetchOpen = false">取消</a-button>
            <a-button type="primary" :disabled="!picked.size" :loading="submitting" @click="submitBatch">
              保存到菜单
            </a-button>
          </span>
        </div>
      </template>
    </a-drawer>

    <!-- 分组管理：把「有哪些分组」提前建好，配菜单时就能从下拉里选 -->
    <a-drawer
      v-model:open="groupsOpen"
      title="分组管理"
      placement="right"
      :width="820"
      :body-style="{ paddingBottom: '80px' }"
    >
      <p class="muted">
        这里只是把分组<b>提前建好</b>：有了它，配菜单时能直接选，不必每处手打分組名；分组还能定
        <b>组间顺序</b>与<b>空组策略</b>。它<b>不</b>是强制约束 —— 菜单项里的分组名仍然是文本，
        各服务动态生成的分组（如仓建设按项目分层的「建模中心」）照旧可以直接写。
      </p>

      <div class="filters">
        <a-radio-group v-model:value="groupScopeFilter" button-style="solid">
          <a-radio-button value="">全部</a-radio-button>
          <a-radio-button value="workbench">工作台壳</a-radio-button>
          <a-radio-button value="project">项目壳</a-radio-button>
        </a-radio-group>
        <a-select
          v-model:value="groupProductFilter"
          style="width: 160px"
          :options="[{ value: '', label: '全部产品' }, ...productChoices]"
        />
      </div>

      <a-table
        :data-source="filteredGroups"
        :columns="groupCols"
        row-key="id"
        size="small"
        :pagination="false"
        class="card card-flush"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'scope'">
            <a-tag :color="record.scope === 'project' ? 'blue' : 'purple'">{{ scopeLabel(record.scope) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'product'">{{ productLabel(record.product) }}</template>
          <template v-else-if="column.key === 'emptyPolicy'">
            <a-tag :color="record.emptyPolicy === 'always' ? 'orange' : 'default'">
              {{ record.emptyPolicy === 'always' ? '保留并置灰' : '整组隐藏' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'act'">
            <a-button size="small" @click="openGroupEdit(record)">编辑</a-button>
            <a-button size="small" danger @click="removeGroup(record)">删除</a-button>
          </template>
        </template>
      </a-table>
      <p v-if="!filteredGroups.length" class="muted">还没有登记任何分组。</p>

      <template #footer>
        <div class="drawer-foot">
          <a-button :loading="importing" @click="importGroups">从候选导入分组</a-button>
          <span>
            <a-button @click="groupsOpen = false">关闭</a-button>
            <a-button type="primary" @click="openGroupCreate">新增分组</a-button>
          </span>
        </div>
      </template>
    </a-drawer>

    <a-modal
      v-model:open="groupOpen"
      :title="groupForm.id ? '编辑分组' : '新增分组'"
      ok-text="保存"
      :confirm-loading="groupBusy"
      @ok="submitGroup"
    >
      <a-form layout="vertical">
        <a-form-item label="归属壳" required>
          <a-radio-group v-model:value="groupForm.scope" :disabled="Boolean(groupForm.id)">
            <a-radio-button value="workbench">工作台壳</a-radio-button>
            <a-radio-button value="project">项目壳</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">壳 + 产品 + 分组名 三样一起才是这个分组的身份，建好之后不能改。</p>
        </a-form-item>
        <a-form-item label="产品" required>
          <a-select v-model:value="groupForm.product" :options="productChoices" :disabled="Boolean(groupForm.id)" />
        </a-form-item>
        <a-form-item label="分组名" required>
          <a-input v-model:value="groupForm.title" placeholder="数据地图" />
        </a-form-item>
        <a-form-item label="组间顺序">
          <a-input-number v-model:value="groupForm.sortOrder" :min="0" style="width: 120px" />
          <p class="muted" style="margin: 4px 0 0">
            数字小的排前面。这里排的是<b>分组之间</b>的先后；分组里面每一项的先后仍由菜单项自己的排序决定。
          </p>
        </a-form-item>
        <a-form-item label="空组策略">
          <a-radio-group v-model:value="groupForm.emptyPolicy">
            <a-radio-button value="hide">整组隐藏</a-radio-button>
            <a-radio-button value="always">保留并置灰</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">
            这一组在当前人那儿<b>一个可用入口都没有</b>时（模块没开通、没派角色、还没配菜单）怎么办。
            「整组隐藏」= 现状行为；「保留并置灰」= 分组照常出现，里面放一条说明为什么点不开。
            <br />
            按规范，<b>「数据地图」这一组要选「保留并置灰」</b>：它要求未开通或未派角色时分组仍在、入口禁用并说明。
          </p>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 新增第一步：选产品 → 选它报出来的页面。默认值全部来自产品，管理员再改。 -->
    <a-modal
      v-model:open="pickOpen"
      title="新增菜单 · 从产品取"
      :width="900"
      :footer="null"
      :body-style="{ paddingBottom: '8px' }"
    >
      <p class="muted">
        选一个产品，再从它<b>自己上报</b>的页面清单里挑一条。菜单名、图标、分组、权限词、路径都会按产品的
        说法预填好，下一步还能改（路径除外 —— 它是那个页面在子应用里的真实路由）。
      </p>
      <a-form layout="vertical">
        <a-form-item label="产品">
          <a-select
            v-model:value="pickProduct"
            style="max-width: 260px"
            :options="pickProductChoices"
            @change="loadPickMenus"
          />
        </a-form-item>
      </a-form>

      <a-spin :spinning="pickLoading">
        <a-alert v-if="pickError" type="warning" show-icon :message="pickError" style="margin-bottom: 12px" />
        <a-table
          v-if="!pickError"
          :data-source="pickMenus"
          :columns="pickCols"
          row-key="id"
          size="small"
          :pagination="{ pageSize: 8, size: 'small' }"
          :scroll="{ y: 360 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'scope'">
              <a-tag :color="record.scope === 'project' ? 'blue' : 'purple'">{{ scopeLabel(record.scope) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'group'">{{ record.group || '未分组' }}</template>
            <template v-else-if="column.key === 'perm'">
              <span v-if="record.perm">{{ record.perm }}</span>
              <span v-else class="muted">不判权</span>
            </template>
            <template v-else-if="column.key === 'configured'">
              <a-tag v-if="alreadyConfigured(record)" color="default">已配置</a-tag>
            </template>
            <template v-else-if="column.key === 'act'">
              <!-- 已配置的仍可再选：同一条路径挂到另一个壳是合法用法，撞了后端会拒 -->
              <a-button size="small" type="link" @click="chooseCandidate(record)">选它</a-button>
            </template>
          </template>
        </a-table>
        <p v-if="!pickLoading && !pickError && !pickMenus.length" class="muted">这个产品没有上报任何可配的页面。</p>
      </a-spin>

      <template #footer>
        <div class="drawer-foot">
          <span class="muted">产品没上报的页面只能手填。</span>
          <span>
            <a-button @click="pickOpen = false">取消</a-button>
            <a-button @click="openBlankForm">直接手填</a-button>
          </span>
        </div>
      </template>
    </a-modal>

    <a-modal
      v-model:open="open"
      :title="form.id ? '编辑菜单' : pathLocked ? '新增菜单 · 来自候选' : '新增菜单'"
      ok-text="保存"
      :confirm-loading="busy"
      @ok="submit"
    >
      <a-form layout="vertical">
        <a-form-item label="产品" required>
          <a-select v-model:value="form.product" :options="productChoices" :disabled="Boolean(form.id)" />
          <p class="muted" style="margin: 4px 0 0">
            只列已登记页面地址的产品 —— 没登记的产品配了菜单也点不开。
          </p>
        </a-form-item>
        <a-form-item label="归属壳" required>
          <a-radio-group v-model:value="form.scope">
            <a-radio-button value="workbench">工作台壳</a-radio-button>
            <a-radio-button value="project">项目壳</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">
            工作台 = 进项目**之前**那一级；项目 = 进项目**之后**。同一条路径可以两个壳各挂一份。
          </p>
        </a-form-item>
        <a-form-item label="菜单名" required>
          <a-input v-model:value="form.label" placeholder="数据地图" />
        </a-form-item>
        <a-form-item label="子应用路径" required>
          <!-- 必须写 `:readonly`（全小写）：antd-vue 声明的是 `readonly` 这个 prop，
               写成 `:read-only` 会驼峰化成 `readOnly` 对不上，结果渲染出一个非标准的
               dead 属性 `read-only="true"` —— 看着锁了、帮助文案也写着只读，但能打字。 -->
          <a-input v-model:value="form.path" :readonly="pathLocked" placeholder="/lineage/tables" />
          <p v-if="pathLocked" class="muted" style="margin: 4px 0 0">
            路径是<b>这个页面在子应用里的真实路由</b>，由产品自己报上来。
            手改出来的路径点进去就是 404，所以从候选起步时这一项只读；
            要挂一个产品没上报的页面，请用「直接手填」。
          </p>
          <p v-else class="muted" style="margin: 4px 0 0">
            该产品里的页面路径。填 <code>/</code> 表示进它的首页。
          </p>
        </a-form-item>
        <a-form-item label="分组标题">
          <a-auto-complete
            v-model:value="form.groupTitle"
            placeholder="数据地图"
            :options="groupOptions(form.scope, form.product)"
          />
          <p class="muted" style="margin: 4px 0 0">
            侧栏里同一分组标题的菜单会收在一起。留空 = 不分组。
            <br />
            下拉里是「分组管理」里提前建好的分组，<b>也可以直接手打</b> —— 各服务按项目分层动态生成的
            分组（如仓建设的「建模中心」）不会出现在清单里，但照样能填。
          </p>
        </a-form-item>
        <a-form-item label="权限词">
          <PermSelect v-model:value="form.perm" :options="optionsOf(form.product)" placeholder="catalog:read" />
          <p class="muted" style="margin: 4px 0 0">
            决定<b>谁看得见这个入口</b>：词表由该产品自己上报（构建时写进它的 <code>menu.json</code>），
            所以这里选不到产品不认识的词。「不判权」= 进得来就看得见。
          </p>
          <p v-if="form.product && !optionsOf(form.product).length" class="muted" style="margin: 4px 0 0">
            没拿到「{{ productLabel(form.product) }}」的词表（服务没在运行 / 还没构建过前端 / 「服务注册」
            里没登记页面地址），暂时可以手填。格式是 <code>域:动作</code>，保存时后端会校验。
          </p>
        </a-form-item>
        <a-form-item label="图标">
          <a-select v-model:value="form.icon" allow-clear placeholder="不选则按产品给通用图标">
            <a-select-option v-for="name in iconChoices" :key="name" :value="name">
              <component :is="icons[name]" v-if="icons[name]" /> <span style="margin-left: 6px">{{ name }}</span>
            </a-select-option>
          </a-select>
          <p class="muted" style="margin: 4px 0 0">
            在这里只是换个显示图标，不影响入口能不能点开 —— 真正决定可见性的是上面的权限词。
          </p>
        </a-form-item>
        <a-form-item label="排序">
          <a-input-number v-model:value="form.sortOrder" :min="0" style="width: 120px" />
          <p class="muted" style="margin: 4px 0 0">数字小的排前面。</p>
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import { api, type MenuCandidate, type NavGroupEmptyPolicy, type NavGroupRow, type NavItemRow, type NavScope, type PermOption } from '../../api/client';
import { ORG_PAGES } from '../../config/pages';
import { productLabel } from '../../config/products';
import { navIcons } from '../../config/navIcons';
import PageHeader from '../../components/PageHeader.vue';
import PermSelect from '../../components/PermSelect.vue';

const rows = ref<NavItemRow[]>([]);
/** 已登记（提前建好）的分组。与菜单项是软约束关系，见页面上的说明。 */
const groups = ref<NavGroupRow[]>([]);
/** 已登记了前端地址的产品码 —— 新增菜单时只能从这里选。 */
const configuredProducts = ref<string[]>([]);
const open = ref(false);
const busy = ref(false);
const icons = navIcons;
const iconNames = Object.keys(navIcons).sort();

/**
 * 图标下拉的候选 = 本平台收录的图标 ∪ **当前值**。
 *
 * <p>补当前值是给「产品报了个本平台没收录的图标名」用的：不加的话下拉框会显示空白，
 * 管理员改别处一保存就顺手把图标清掉了。产品与 org 的图标库是两份清单，漂移是常态
 * （`ProjectOutlined` 就是这么漏掉的），所以这里必须兜底而不能假定两边一致。
 */
const iconChoices = computed(() => {
  const names = new Set(iconNames);
  if (form.icon) names.add(form.icon);
  return [...names].sort();
});

const scopeFilter = ref<'' | NavScope>('');
const scopeChoices = [
  { value: 'workbench', label: '工作台壳' },
  { value: 'project', label: '项目壳' },
];

function scopeLabel(scope: NavScope) {
  return scope === 'project' ? '项目壳' : '工作台壳';
}

/**
 * 分组名下拉候选（`a-auto-complete` 的 options）。
 *
 * <p>按当前「壳 + 产品」过滤 —— 分组就是按这两维归属的，换了壳或产品，
 * 候选清单跟着换。用 `a-auto-complete` 而不是 `a-select` 是刻意的：分组名是<b>软约束</b>，
 * 未登记的名字（各服务动态生成的那些）必须还能手打进去。
 */
function groupOptions(scope: NavScope, product: string) {
  return groups.value
    .filter((g) => g.scope === scope && g.product === product && g.title)
    .map((g) => ({ value: g.title }));
}

// ---- 权限词词表 ----
/**
 * 各产品**自报**的权限词表，按产品缓存。
 *
 * <p>由产品前端构建时写进 `menu.json`（见各服务 `config/navData.ts` 的 `PERM_OPTIONS`），
 * 后端按「服务注册」里登记的页面地址拉。org 侧不硬编码一份 —— 硬编码的那份必然与产品漂移，
 * 而漂移的后果正是「管理员选了一个产品不认识的词 → 那个菜单永远置灰」。
 *
 * <p>拉不到（老版本服务 / 页面地址没登记）时按空表处理，控件回落到可手填 ——
 * 不让一处配置缺失把配菜单这件事整个锁死。
 */
const permOptions = reactive<Record<string, PermOption[]>>({});
const permLoading = reactive<Record<string, boolean>>({});

async function ensurePermOptions(product: string) {
  if (!product || permOptions[product] || permLoading[product]) return;
  permLoading[product] = true;
  try {
    permOptions[product] = (await api.platform.productPerms(product)).perms;
  } catch {
    // 刻意不弹提示：它是页面的辅助信息，弹窗会盖住真正要报告的保存结果。
    // 表现是那个字段变成可手填（`PermSelect` 的空表分支），管理员看得出来。
    permOptions[product] = [];
  } finally {
    permLoading[product] = false;
  }
}

function optionsOf(product: string): PermOption[] {
  return permOptions[product] ?? [];
}

// ---- 分组管理 ----
const groupsOpen = ref(false);
const importing = ref(false);
const groupOpen = ref(false);
const groupBusy = ref(false);
const groupScopeFilter = ref<'' | NavScope>('');
const groupProductFilter = ref('');
const groupForm = reactive({
  id: '',
  scope: 'workbench' as NavScope,
  product: '',
  title: '',
  sortOrder: 0,
  emptyPolicy: 'hide' as NavGroupEmptyPolicy,
});

const groupCols = [
  { title: '归属壳', key: 'scope', width: 96 },
  { title: '产品', key: 'product', width: 130 },
  { title: '分组名', dataIndex: 'title' },
  { title: '组间顺序', dataIndex: 'sortOrder', width: 88 },
  { title: '空组策略', key: 'emptyPolicy', width: 120 },
  { title: '', key: 'act', width: 140 },
];

const filteredGroups = computed(() =>
  groups.value.filter(
    (g) => (!groupScopeFilter.value || g.scope === groupScopeFilter.value)
      && (!groupProductFilter.value || g.product === groupProductFilter.value),
  ),
);

function openGroups() {
  groupsOpen.value = true;
}

function openGroupCreate() {
  Object.assign(groupForm, {
    id: '',
    scope: groupScopeFilter.value || 'workbench',
    product: groupProductFilter.value || configuredProducts.value[0] || '',
    title: '',
    sortOrder: 0,
    // 默认与现状一致（空分组不渲染）。要「始终出现」必须显式选，见弹窗里的说明。
    emptyPolicy: 'hide',
  });
  groupOpen.value = true;
}

function openGroupEdit(row: NavGroupRow) {
  Object.assign(groupForm, {
    id: row.id,
    scope: row.scope,
    product: row.product,
    title: row.title,
    sortOrder: row.sortOrder,
    emptyPolicy: row.emptyPolicy,
  });
  groupOpen.value = true;
}

async function submitGroup() {
  if (!groupForm.product.trim() || !groupForm.title.trim()) {
    message.warning('请填写产品和分组名');
    return;
  }
  groupBusy.value = true;
  const body = {
    scope: groupForm.scope,
    product: groupForm.product.trim(),
    title: groupForm.title.trim(),
    sortOrder: groupForm.sortOrder ?? 0,
    emptyPolicy: groupForm.emptyPolicy,
  };
  try {
    if (groupForm.id) {
      const res = await api.platform.updateNavGroup(groupForm.id, body);
      // 改名会级联改菜单项里的分组名，把条数说出来 —— 不然管理员不知道侧栏会不会跟着变
      message.success(res.renamedItems ? `已保存，${res.renamedItems} 条菜单的分组名跟着改了` : '已保存');
    } else {
      await api.platform.createNavGroup(body);
      message.success('已保存');
    }
    groupOpen.value = false;
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    groupBusy.value = false;
  }
}

async function removeGroup(row: NavGroupRow) {
  try {
    const res = await api.platform.deleteNavGroup(row.id);
    await load();
    // 删除不阻塞、也不清空菜单项的分组名（那等于静默改菜单），所以要把「还有几条在用」说出来
    if (res.referenced) {
      message.warning(`已删除分组登记，仍有 ${res.referenced} 条菜单写着「${row.title}」，它们照常显示`);
    } else {
      message.success('已删除');
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

/**
 * 从各服务的候选里把出现过的分组名一次性建成登记项。
 *
 * <p>逐条 POST 而不是走后端批量端点：这一步是<b>一次性配置动作</b>（一个租户的
 * 分组撑死十来条），而且下面已经按「已登记集合」预过滤过，不会出现撞唯一约束的 400 风暴。
 * 菜单项的批量端点是因为「一次勾十几条」是常态才必须做的，这里不是。
 */
async function importGroups() {
  importing.value = true;
  try {
    const res = await api.platform.navCandidates();
    const existing = new Set(groups.value.map((g) => `${g.scope}/${g.product}/${g.title}`));
    const seen = new Set<string>();
    let created = 0;
    let skipped = 0;
    let failed = 0;
    let order = 10;
    for (const p of res.products) {
      if (!p.ok) continue;
      for (const m of p.menus) {
        // 候选里的 group 是自由文本，空串表示「这个服务说自己不分组」——
        // 它不是合法的分组名（后端会 400），要显式跳过
        const title = (m.group || '').trim();
        if (!title) continue;
        const key = `${m.scope}/${p.product}/${title}`;
        if (seen.has(key)) continue;
        seen.add(key);
        if (existing.has(key)) {
          skipped++;
          continue;
        }
        try {
          await api.platform.createNavGroup({ scope: m.scope, product: p.product, title, sortOrder: order, emptyPolicy: 'hide' });
          // 按遇到顺序给 10/20/30…，导入后拖动数字就能调组间顺序
          order += 10;
          created++;
        } catch {
          failed++;
        }
      }
    }
    await load();
    const parts = [`新增 ${created} 个分组`];
    if (skipped) parts.push(`已登记 ${skipped} 个`);
    if (failed) parts.push(`失败 ${failed} 个`);
    message.success(parts.join('，'));
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    importing.value = false;
  }
}

// ---- 拉取候选 ----
const fetchOpen = ref(false);
const fetching = ref(false);
const submitting = ref(false);
const candidates = ref<{ product: string; url?: string; ok: boolean; error: string; menus: MenuCandidate[] }[]>([]);
/** 每个候选的可编辑副本（归属壳、分组、菜单名、权限词、排序都能改；路径只读）。 */
const draft = reactive<Record<string, { scope: NavScope; group: string; label: string; perm: string; sortOrder: number }>>({});
const picked = ref(new Set<string>());

/** 右侧清单的列。归属壳往下都能改；路径只读（它必须与子应用的路由一致）。 */
const pickedCols = [
  { title: '归属壳', key: 'scope', width: 116 },
  { title: '分组', key: 'group', width: 132 },
  { title: '菜单名', key: 'label', width: 150 },
  { title: '子应用路径', dataIndex: 'path' },
  { title: '权限词', key: 'perm', width: 128 },
  { title: '排序', key: 'sort', width: 84 },
  { title: '', key: 'act', width: 68 },
];

const fetchableCount = computed(() => candidates.value.filter((c) => c.ok && c.menus.length).length);

/** 候选的扁平表，带上它属于哪个产品。树、右侧清单、批量提交都从它派生。 */
const allCandidates = computed(() => candidates.value.flatMap((g) => g.menus.map((m) => ({ product: g.product, m }))));

/** 拉取失败的产品：进不了树，在树上方单独说明原因（带着实际请求的地址）。 */
const failedCandidates = computed(() => candidates.value.filter((c) => !c.ok));

type CandNode = {
  key: string;
  title: string;
  selectable?: boolean;
  disabled?: boolean;
  isLeaf?: boolean;
  children?: CandNode[];
};

function leafKey(m: MenuCandidate) {
  return `m:${m.id}`;
}

/**
 * 左侧菜单树 = 产品 → 壳 · 分组 → 菜单项。
 *
 * <p>分组这一层刻意**不读草稿值**：右侧能把归属壳与分组名改掉，树若跟着重排，
 * 改一个字整棵树就跳一下、勾选位置找不着。树只表达「服务报上来的样子」。
 *
 * <p>已经配过的菜单在树上禁用 —— 同一个壳下同产品同路径只该有一条。
 */
const candTree = computed<CandNode[]>(() =>
  candidates.value
    .filter((g) => g.menus.length)
    .map((g) => {
      const buckets = new Map<string, { scope: NavScope; group: string; menus: MenuCandidate[] }>();
      for (const m of g.menus) {
        const k = `${m.scope}\u0000${m.group}`;
        const b = buckets.get(k) ?? { scope: m.scope, group: m.group, menus: [] };
        b.menus.push(m);
        buckets.set(k, b);
      }
      return {
        key: `p:${g.product}`,
        title: `${productLabel(g.product)}（${g.menus.length} 项）`,
        selectable: false,
        children: [...buckets.values()].map((b) => ({
          key: `g:${g.product}\u0000${b.scope}\u0000${b.group}`,
          title: `${scopeLabel(b.scope)} · ${b.group || '未分组'}`,
          selectable: false,
          children: b.menus.map((m) => ({
            key: leafKey(m),
            title: isConfigured(g.product, m) ? `${m.label}（已配置）` : m.label,
            isLeaf: true,
            disabled: isConfigured(g.product, m),
          })),
        })),
      };
    })
);

/**
 * 树上的勾选态由**已选集合**推出来，不另存一份。
 *
 * <p>只给叶子 key：rc-tree 在非严格模式下会自己把父节点的全选/半选算出来，
 * 所以「建模中心」这种分组的对勾不必我们维护 —— 组里勾了一半，它自己就是半选。
 */
const checkedKeys = computed(() => [...picked.value].map((id) => `m:${id}`));

/**
 * 树上勾/取消 → 重算已选集合。
 *
 * <p>不读「这一次改了哪个节点」，而是拿勾选后的**全量 key** 重算：勾分组会连带一整片、
 * 勾叶子只影响自己，两种情形用同一条规则就都对，不必再区分节点是不是叶子。
 *
 * <p>参数类型按 `unknown` 收 —— antd 的 `check` 事件在严格/非严格模式下签名不同，
 * 写死一种会让另一个模式编译不过（与 `product-roles.vue` 同一处理）。
 */
function onTreeCheck(raw: unknown) {
  const checked = new Set((Array.isArray(raw) ? raw : []).map(String));
  const next = new Set<string>();
  for (const { product, m } of allCandidates.value) {
    // 已配置的在树上禁用；万一 rc-tree 把它一并报进来，这里再挡一道 ——
    // 放进右侧会让保存时整条被后端跳过，看起来像「保存没生效」
    if (checked.has(leafKey(m)) && !isConfigured(product, m)) next.add(m.id);
  }
  picked.value = next;
}

/** 右侧清单 = 已选项，按候选顺序（也就是树上的顺序），并带上产品码供下拉用。 */
const pickedRows = computed(() =>
  allCandidates.value.filter(({ m }) => picked.value.has(m.id)).map(({ product, m }) => ({ ...m, product }))
);

/**
 * 该候选是否已经配过（同一个壳下同产品同路径）。已配置的默认不勾。
 *
 * <p>归属壳取**当前草稿值**而不是候选自报值：管理员把「工作台」改成「项目」之后，
 * 这一行的「已配置」判定要跟着改 —— 否则一条已经挂在项目壳的菜单会被标成可勾，
 * 勾了之后后端按「已配置」跳过，看起来像保存没生效。
 */
function isConfigured(product: string, record: MenuCandidate) {
  const scope = draft[record.id]?.scope ?? record.scope;
  return rows.value.some((r) => r.scope === scope && r.product === product && r.path === record.path);
}

/** 右侧清单里移除一项（右侧是「取消勾选」的另一个入口，删完树上的勾也跟着灭）。 */
function unpick(record: MenuCandidate) {
  const next = new Set(picked.value);
  next.delete(record.id);
  picked.value = next;
}

async function openFetch() {
  fetchOpen.value = true;
  fetching.value = true;
  picked.value = new Set();
  try {
    const res = await api.platform.navCandidates();
    candidates.value = res.products;
    for (const group of res.products) {
      // 抽屉里每一行的权限词都是下拉，词表按产品拉一次（并发、失败静默回落手填）
      if (group.ok) void ensurePermOptions(group.product);
      for (const m of group.menus) {
        draft[m.id] = { scope: m.scope, group: m.group, label: m.label, perm: m.perm, sortOrder: m.sort };
      }
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    fetching.value = false;
  }
}

async function submitBatch() {
  const items = allCandidates.value
    .filter(({ m }) => picked.value.has(m.id))
    .map(({ product, m }) => ({
      product,
      scope: draft[m.id].scope,
      groupTitle: draft[m.id].group,
      label: draft[m.id].label,
      // 路径只读：它必须与子应用里的路由一致，手改出来的路径点进去就是 404
      path: m.path,
      icon: m.icon,
      perm: draft[m.id].perm,
      sortOrder: draft[m.id].sortOrder,
    }));
  if (!items.length) return;

  submitting.value = true;
  try {
    const res = await api.platform.createNavItemsBatch(items);
    const parts = [`新增 ${res.created.length} 条`];
    if (res.skipped.length) parts.push(`跳过 ${res.skipped.length} 条（已配置）`);
    if (res.failed.length) parts.push(`失败 ${res.failed.length} 条`);
    if (res.failed.length) {
      // 失败要能对上号，否则管理员只知道「有几条没进去」
      message.warning(`${parts.join('，')}：第 ${res.failed.map((f) => f.index).join('、')} 项 —— ${res.failed[0].reason}`);
    } else {
      message.success(parts.join('，'));
    }
    await load();
    await openFetch();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    submitting.value = false;
  }
}

// ---- 手填表单（候选拉不到时的兜底通道） ----
const form = reactive({
  id: '',
  product: '',
  scope: 'workbench' as NavScope,
  groupTitle: '',
  label: '',
  path: '/',
  perm: '',
  icon: '',
  sortOrder: 0,
  enabled: true,
});

// 表单里换了产品就换一份词表。不监听的话，新建时把产品从 A 改成 B，
// 下拉里还是 A 的词 —— 选出来的词 B 不认，保存时后端拒，而管理员看不出为什么。
watch(() => form.product, (p) => { if (p) void ensurePermOptions(p); });

/**
 * 可选产品 = 已登记前端地址的产品 ∪ 当前这条菜单的产品。
 *
 * <p>并上后者是因为编辑一条老菜单时，它的产品可能已经被「服务注册」移除了：
 * 不带上的话下拉框会显示空白，一保存就把产品改成别的。
 */
const productChoices = computed(() => {
  const codes = new Set(configuredProducts.value);
  if (form.product) codes.add(form.product);
  return [...codes].map((value) => ({ value, label: productLabel(value) }));
});

/** 「从产品取」那一步的产品下拉：**只**列已登记页面地址的产品（这里没有「并上当前值」的理由，与表单不同）。 */
const pickProductChoices = computed(() =>
  configuredProducts.value.map((value) => ({ value, label: productLabel(value) })),
);

const canCreate = computed(() => configuredProducts.value.length > 0);

/**
 * 树节点：壳 → 产品 → 分组 → 菜单项。只有叶子是可编辑的菜单项。
 *
 * <p>分支节点把 NavItemRow 的字段填成空值而不是留空缺失，这样模板里各处 `record.xxx`
 * 与平铺表格时完全一样，不必因为「这一列只在叶子上有意义」而到处加判断；
 * 只在真会误导的地方（图标 / 排序 / 状态 / 操作）显式判断 nodeType。
 *
 * <p>叶子的 id 就是原始行的 id —— 编辑、删除用的都是它；分支用 `~` 前缀的合成 id，
 * 与真实 id（`nav-...`）不会撞。
 */
type NavTreeNode = NavItemRow & {
  name: string;
  nodeType: 'item' | 'branch';
  depth: number;
  /** 叶子对应的原始行。 */
  item?: NavItemRow;
  /** 分组分支：已登记但这一壳这一产品下一条菜单都没有。 */
  emptyGroup?: boolean;
  /** 分组分支：这个分组名只存在于菜单项里，没在「分组管理」里登记过。 */
  unregisteredGroup?: boolean;
  children?: NavTreeNode[];
};

const SCOPE_ORDER: NavScope[] = ['workbench', 'project'];

function leaf(row: NavItemRow, depth: number): NavTreeNode {
  return { ...row, name: row.label, nodeType: 'item', depth, item: row };
}

/** 分支的排序取子树里最小的那个 —— 与侧栏一致（侧栏也是按 sortOrder 排的）。 */
function branch(id: string, name: string, depth: number, scope: NavScope, children: NavTreeNode[]): NavTreeNode {
  return {
    id,
    product: '',
    scope,
    groupTitle: '',
    label: name,
    icon: '',
    path: '',
    perm: '',
    sortOrder: children.length ? Math.min(...children.map((c) => c.sortOrder)) : 0,
    enabled: true,
    name,
    nodeType: 'branch',
    depth,
    children,
  };
}

function bySortThenName(a: NavTreeNode, b: NavTreeNode) {
  return a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh');
}

/** 这个分组名有没有在「分组管理」里登记过。软约束，所以查不到是正常情况。 */
function registeredGroup(scope: NavScope, product: string, title: string) {
  return groups.value.find((g) => g.scope === scope && g.product === product && g.title === title);
}

/**
 * 树：壳 → 产品 → 分组 → 菜单项。
 *
 * <p>分组层是<b>并集</b>：菜单项里出现的分组名 ∪ 已登记的分组。少了后者，
 * 「提前建好的空分组」在树里就看不见 —— 管理员建完找不到它，会以为没保存成功。
 *
 * <p>产品层同理要并上「只登记了分组、还没有任何菜单项」的产品，否则为一个新产品
 * 预建分组时整个产品节点都不会出现。
 */
const treeRows = computed<NavTreeNode[]>(() => {
  const scopeFilterValue = scopeFilter.value;
  const list = scopeFilterValue ? rows.value.filter((r) => r.scope === scopeFilterValue) : rows.value;
  const regs = scopeFilterValue ? groups.value.filter((g) => g.scope === scopeFilterValue) : groups.value;
  const scopes = SCOPE_ORDER.filter(
    (s) => list.some((r) => r.scope === s) || regs.some((g) => g.scope === s),
  );

  return scopes.map((scope) => {
    const inScope = list.filter((r) => r.scope === scope);
    const regsInScope = regs.filter((g) => g.scope === scope);
    const products = [...new Set([...inScope.map((r) => r.product), ...regsInScope.map((g) => g.product)])];

    const productNodes = products
      .map((product) => {
        const byGroup = new Map<string, NavItemRow[]>();
        for (const r of inScope.filter((x) => x.product === product)) {
          const g = r.groupTitle || '';
          byGroup.set(g, [...(byGroup.get(g) ?? []), r]);
        }
        // 已登记但还没有菜单项的分组也要有分支（值为空数组）
        for (const g of regsInScope.filter((x) => x.product === product)) {
          if (!byGroup.has(g.title)) byGroup.set(g.title, []);
        }

        const groupNodes = [...byGroup.entries()]
          .map(([group, groupRows]) => {
            const reg = registeredGroup(scope, product, group);
            const node = branch(
              `~${scope}/${product}/${group}`,
              group || '未分组',
              2,
              scope,
              [...groupRows].sort((a, b) => a.sortOrder - b.sortOrder).map((r) => leaf(r, 3)),
            );
            if (!groupRows.length) node.emptyGroup = true;
            else if (!reg) node.unregisteredGroup = true;
            return node;
          })
          // 排序与侧栏一致：登记过的按它的组间顺序在前，没登记的（各服务动态生成的）垫后。
          // 用登记的 sortOrder 而不是子树最小值，否则「提前建好的空分组」会被算成 0 冒到最前。
          .sort((a, b) => {
            const ra = registeredGroup(scope, product, a.name === '未分组' ? '' : a.name);
            const rb = registeredGroup(scope, product, b.name === '未分组' ? '' : b.name);
            if (Boolean(ra) !== Boolean(rb)) return ra ? -1 : 1;
            const oa = ra ? ra.sortOrder : a.sortOrder;
            const ob = rb ? rb.sortOrder : b.sortOrder;
            return oa - ob || a.name.localeCompare(b.name, 'zh');
          });

        return branch(`~${scope}/${product}`, productLabel(product), 1, scope, groupNodes);
      })
      .sort(bySortThenName);
    return branch(`~${scope}`, scopeLabel(scope), 0, scope, productNodes);
  });
});

const expandedKeys = ref<string[]>([]);

function collectBranchKeys(nodes: NavTreeNode[]): string[] {
  return nodes.flatMap((n) => (n.nodeType === 'branch' ? [n.id, ...collectBranchKeys(n.children ?? [])] : []));
}

// 数据一变就整树展开。用受控的 expandedRowKeys 而不是 defaultExpandAllRows ——
// 后者只在首次渲染生效，切「归属壳」筛选后新树是收起的。
watch(treeRows, (list) => { expandedKeys.value = collectBranchKeys(list); }, { immediate: true });

function onExpand(expanded: boolean, record: NavTreeNode) {
  const next = new Set(expandedKeys.value);
  if (expanded) next.add(record.id);
  else next.delete(record.id);
  expandedKeys.value = [...next];
}

function rowClass(record: NavTreeNode) {
  return record.nodeType === 'branch' ? 'nav-branch-row' : '';
}

const cols = [
  { title: '菜单 / 目录', key: 'name' },
  { title: '子应用路径', dataIndex: 'path' },
  { title: '权限词', dataIndex: 'perm', width: 120 },
  { title: '图标', key: 'icon', width: 70 },
  { title: '排序', key: 'sortOrder', width: 66 },
  { title: '状态', key: 'enabled', width: 76 },
  { title: '', key: 'act', width: 150 },
];

async function load() {
  try {
    const [items, services, groupRows] = await Promise.all([
      api.platform.navItems(),
      api.platform.services(),
      api.platform.navGroups(),
    ]);
    rows.value = items;
    configuredProducts.value = services.filter((s) => s.frontendUrl).map((s) => s.product);
    groups.value = groupRows;
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

/**
 * 「新增菜单」第一步：先选产品、再选它报出来的一个页面。
 *
 * <p>为什么不让管理员直接手填：菜单名、图标、分组、权限词、路径这五样里，
 * **后四样产品自己知道**（它的 `navData.ts` 里就写着），手打一份等于让人抄一遍，
 * 抄错的后果是路径 404 或权限词判否 —— 两种都不报错，只在用户点进去时才发现。
 *
 * <p>手填通道<b>保留</b>（弹窗底部的「直接手填」）：产品前端还没构建过时拉不到候选，
 * 那是唯一的出路；编辑已有菜单走的也还是它。
 */
const pickOpen = ref(false);
const pickProduct = ref('');
const pickLoading = ref(false);
const pickMenus = ref<MenuCandidate[]>([]);
const pickError = ref('');

/** 子应用路径是否只读 —— 从候选预填时只读，见模板里的说明。 */
const pathLocked = ref(false);

const pickCols = [
  { title: '归属壳', key: 'scope', width: 96 },
  { title: '分组', dataIndex: 'group', width: 130 },
  { title: '菜单名', dataIndex: 'label', width: 160 },
  { title: '子应用路径', dataIndex: 'path' },
  { title: '权限词', dataIndex: 'perm', width: 140 },
  { title: '', key: 'configured', width: 76 },
  { title: '', key: 'act', width: 76 },
];

function openCreate() {
  pickProduct.value = configuredProducts.value[0] ?? '';
  pickMenus.value = [];
  pickError.value = '';
  pickOpen.value = true;
  if (pickProduct.value) void loadPickMenus();
}

async function loadPickMenus() {
  if (!pickProduct.value) return;
  pickLoading.value = true;
  pickError.value = '';
  pickMenus.value = [];
  void ensurePermOptions(pickProduct.value);
  try {
    const res = await api.platform.navCandidates();
    const found = res.products.find((p) => p.product === pickProduct.value);
    if (!found) {
      // 「服务注册」里没有这个产品：说清去哪儿补，而不是只显示一个空列表
      pickError.value = `「服务注册」里没有登记 ${productLabel(pickProduct.value)} 的页面地址，没法从它取候选`;
    } else if (!found.ok) {
      pickError.value = found.error;
    } else {
      pickMenus.value = found.menus;
    }
  } catch (e) {
    pickError.value = e instanceof Error ? e.message : String(e);
  } finally {
    pickLoading.value = false;
  }
}

/** 这条候选在当前产品 + 它自报的壳下是不是已经配过了（同壳同产品同路径）。 */
function alreadyConfigured(m: MenuCandidate) {
  return rows.value.some((r) => r.scope === m.scope && r.product === pickProduct.value && r.path === m.path);
}

/** 用候选预填表单 —— 默认值全部取自产品，管理员可以改（`path` 除外，见模板）。 */
function chooseCandidate(m: MenuCandidate) {
  Object.assign(form, {
    id: '',
    product: pickProduct.value,
    scope: m.scope,
    groupTitle: m.group ?? '',
    label: m.label,
    path: m.path,
    perm: m.perm ?? '',
    icon: m.icon ?? '',
    sortOrder: m.sort ?? 0,
    enabled: true,
  });
  pathLocked.value = true;
  pickOpen.value = false;
  open.value = true;
}

/** 兜底：不经过候选直接手填（产品前端还没构建过时用）。 */
function openBlankForm() {
  Object.assign(form, {
    id: '',
    product: pickProduct.value || configuredProducts.value[0] || '',
    scope: 'workbench',
    groupTitle: '',
    label: '',
    path: '/',
    perm: '',
    icon: '',
    sortOrder: 0,
    enabled: true,
  });
  pathLocked.value = false;
  pickOpen.value = false;
  open.value = true;
}

function openEdit(row: NavItemRow) {
  Object.assign(form, {
    id: row.id,
    product: row.product,
    scope: row.scope,
    groupTitle: row.groupTitle,
    label: row.label,
    path: row.path,
    perm: row.perm,
    icon: row.icon,
    sortOrder: row.sortOrder,
    enabled: row.enabled,
  });
  // 编辑已有菜单时路径可改（管理员可能在产品换了路由后回来同步），
  // 只有「从候选起步」那条路锁定它 —— 那种情况下候选给的路径就是对的，改了必错。
  pathLocked.value = false;
  void ensurePermOptions(row.product);
  open.value = true;
}

async function submit() {
  if (!form.product.trim() || !form.label.trim()) {
    message.warning('请填写产品和菜单名');
    return;
  }
  busy.value = true;
  const body = {
    product: form.product.trim(),
    scope: form.scope,
    groupTitle: form.groupTitle.trim(),
    label: form.label.trim(),
    path: form.path.trim() || '/',
    perm: form.perm.trim(),
    icon: form.icon || '',
    sortOrder: form.sortOrder ?? 0,
    enabled: form.enabled,
  };
  try {
    if (form.id) await api.platform.updateNavItem(form.id, body);
    else await api.platform.createNavItem(body);
    open.value = false;
    message.success('已保存');
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function remove(id: string) {
  try {
    await api.platform.deleteNavItem(id);
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

onMounted(load);
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 0 0 12px;
}
/* 分支行（壳 / 产品 / 分组）与叶子区分开：层次靠缩进，身份靠底色与字重 */
:deep(.nav-branch-row) > td {
  background: #f2f3f5;
  font-weight: 600;
}
:deep(.nav-branch-row:hover) > td {
  background: #e9ebee;
}
.node-branch {
  color: rgba(0, 0, 0, 0.72);
}
/* 左树右选：左边固定宽（树的名字都很短），右边吃掉剩下的宽度放可编辑的清单 */
.pick-split {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.pick-left {
  border-right: 1px solid var(--line);
  padding-right: 12px;
  max-height: calc(100vh - 260px);
  overflow: auto;
}
.pick-right {
  min-width: 0;
}
.pick-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.pick-head .muted {
  font-size: 12px;
}
.empty-pick {
  border: 1px dashed var(--line);
  border-radius: 8px;
  padding: 24px 16px;
  font-size: 13px;
  line-height: 1.7;
}
.cand-fail {
  margin-bottom: 16px;
}
.cand-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.cand-url {
  margin: 0 0 6px;
  font-size: 12px;
}
.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
