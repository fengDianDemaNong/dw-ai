<template>
  <div class="page">
    <PageHeader
      title="菜单管理"
      subtitle="一棵树管两个壳：工作台（进项目之前）与项目（进项目之后）。节点有两类 —— org 自己的页面，以及从各产品挂上来的页面；两类都可以无限层级，类目由你编排，产品的层级由产品自己报。"
    >
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button @click="openFetch">从服务拉取菜单</a-button>
        <a-button :disabled="!hasProducts" @click="openPicker('create')">挂载产品菜单</a-button>
        <a-button type="primary" @click="openCreate(null)">新增主菜单</a-button>
      </template>
    </PageHeader>

    <p v-if="!hasProducts" class="muted card">
      还没有登记任何产品的页面地址，产品菜单会挂不上来。先到
      <router-link :to="ORG_PAGES.platformServices">服务注册</router-link>
      填上要嵌入的产品。<b>org 自己的页面不受影响</b>，照常能在下面配。
    </p>

    <div class="filters">
      <a-radio-group v-model:value="scopeFilter" button-style="solid">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button value="workbench">工作台壳</a-radio-button>
        <a-radio-button value="project">项目壳</a-radio-button>
      </a-radio-group>
    </div>

    <a-table
      :data-source="tableRows"
      :columns="cols"
      row-key="id"
      :pagination="false"
      size="small"
      class="card card-flush"
      :indent-size="14"
      :row-class-name="rowClass"
      v-model:expanded-row-keys="expandedKeys"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <!-- 壳根：前端合成的两行，不是菜单表里的行（工作台壳 / 项目壳） -->
          <a-tag v-if="isShell(record)" :color="record.scope === 'project' ? 'blue' : 'purple'">
            {{ record.label }}
          </a-tag>
          <template v-else>
            <span :class="{ 'node-dir': isDir(record) && !record.mounted }">{{ record.label }}</span>
            <a-tooltip v-if="record.mounted" :title="mountedTip(record)">
              <a-tag color="cyan" style="margin-left: 6px">挂载</a-tag>
            </a-tooltip>
            <a-tooltip v-if="isMismatch(record)" :title="mismatchTip(record)">
              <a-tag color="orange" style="margin-left: 6px">已失配</a-tag>
            </a-tooltip>
            <a-tag v-if="record.adminOnly" style="margin-left: 6px">仅管理员</a-tag>
            <a-tag v-if="!record.enabled" style="margin-left: 6px">停用</a-tag>
          </template>
        </template>

        <template v-else-if="column.key === 'path'">
          <template v-if="!isShell(record)">
            <a-tag v-if="record.product" style="margin-right: 6px">{{ productLabel(record.product) }}</a-tag>
            <template v-if="record.mounted">
              <span class="muted">内容来自产品清单：<code>{{ record.ref }}</code></span>
            </template>
            <span v-else-if="!record.path" class="muted">目录（不可点）</span>
            <code v-else>{{ record.path }}</code>
          </template>
        </template>

        <template v-else-if="column.key === 'perm'">
          <span v-if="isShell(record)"></span>
          <code v-else-if="record.perm">{{ record.perm }}</code>
          <span v-else class="muted">不判权</span>
        </template>

        <template v-else-if="column.key === 'icon'">
          <component v-if="!isShell(record) && icons[record.icon]" :is="icons[record.icon]" />
          <span v-else-if="!isShell(record)" class="muted">通用</span>
        </template>

        <template v-else-if="column.key === 'sortOrder'">
          <span v-if="!isShell(record)">{{ record.sortOrder }}</span>
        </template>

        <template v-else-if="column.key === 'emptyPolicy'">
          <template v-if="!isShell(record) && isDir(record)">
            <a-tag :color="record.emptyPolicy === 'always' ? 'orange' : 'default'">
              {{ record.emptyPolicy === 'always' ? '保留并置灰' : '隐藏' }}
            </a-tag>
          </template>
        </template>

        <template v-else-if="column.key === 'act'">
          <a-button size="small" type="link" @click="openCreate(record)">
            {{ isShell(record) ? '新增主菜单' : '新增子菜单' }}
          </a-button>
          <template v-if="!isShell(record)">
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-button size="small" danger @click="remove(record)">删除</a-button>
          </template>
        </template>
      </template>
    </a-table>

    <p v-if="rows.length" class="muted">
      <b>两类节点的区别</b>：org 自己的页面（路径形如 <code>/org/...</code>，直接跳）与从产品挂上来的页面。
      产品那一类又分两种：<b>手工复制</b>（把产品清单里的某一条抄成一行，产品以后改了这里不跟着变）与
      <b>挂载</b>（只记「产品 + 清单里的节点 id」，内容是每次渲染时现取的 —— 产品新增子菜单，侧栏自动跟上）。
    </p>
    <p v-if="rows.length" class="muted">
      <b>产品页面里的路径是子应用内</b>的路径（如 <code>/lineage/tables</code>），不是本平台的地址 ——
      本平台会拼成 <code>/org/embed/{产品}{路径}</code>（项目壳下多一级 <code>/org/project/{项目}</code>）。
      挂载节点展开出来的项会<b>覆盖同路径的手工行</b>：同一个页面既手工复制又挂载时，侧栏只显示产品报的那一条。
    </p>
    <p v-if="failedReports.length" class="muted">
      这些产品的清单没拉到，下面标不了「已失配」也列不出它们的可挂载项：
      {{ failedReports.map((p) => `${productLabel(p.product)}（${p.error || '拉取失败'}）`).join('、') }}。
      修好「服务注册」里的页面地址，或确认产品前端已经构建过。
    </p>

    <!-- 从服务拉取候选：勾一棵子树 = 整支抄成手工行（引用不是复制，要「跟着产品变」请用挂载） -->
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
        勾选后抄进菜单表的是<b>副本</b>；要让侧栏跟着产品走，请用上面的「挂载产品菜单」。
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

        <!-- 左树右选：左边是产品上报的菜单树（产品 → 目录 → 子菜单…），右边是即将导入的清单。
             勾一个目录 = 整支导入。 -->
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
              <span v-if="pickedRows.length" class="muted">
                归属壳、菜单名、权限词、排序都能改了再保存；层级照产品的清单落下来
              </span>
            </div>

            <a-table
              v-if="pickedRows.length"
              :data-source="pickedRows"
              :columns="pickedCols"
              row-key="key"
              size="small"
              :pagination="false"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'scope'">
                  <a-select
                    v-model:value="draft[record.key].scope"
                    size="small"
                    style="width: 108px"
                    :options="scopeChoices"
                  />
                </template>
                <template v-else-if="column.key === 'title'">
                  <a-input v-model:value="draft[record.key].title" size="small" />
                </template>
                <template v-else-if="column.key === 'path'">
                  <code v-if="record.node.path">{{ record.node.path }}</code>
                  <span v-else class="muted">目录</span>
                </template>
                <template v-else-if="column.key === 'perm'">
                  <!-- 与手填表单用的是同一个控件：两处各写一遍必然会漂成「同一件事能填的词不一样」 -->
                  <PermSelect
                    v-model:value="draft[record.key].perm"
                    :options="optionsOf(record.product)"
                    placeholder="不判权"
                  />
                </template>
                <template v-else-if="column.key === 'sort'">
                  <a-input-number
                    v-model:value="draft[record.key].sortOrder"
                    size="small"
                    :min="0"
                    style="width: 72px"
                  />
                </template>
                <template v-else-if="column.key === 'act'">
                  <a-button size="small" @click="unpick(record.key)">移除</a-button>
                </template>
              </template>
            </a-table>

            <p v-else class="muted empty-pick">
              在左边勾选要挂上来的菜单。勾<b>一个目录</b>会把这一支下面的菜单一起选进来 ——
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

    <!-- 挂载 / 从产品取：同一个选择器，两种落点（直接建挂载行 / 回填表单） -->
    <a-modal
      v-model:open="pickOpen"
      :title="
        pickTarget === 'create'
          ? '挂载产品菜单'
          : pickTarget === 'mounted'
            ? '选一个要挂载的产品节点'
            : '从产品清单里挑一条'
      "
      :width="900"
      :footer="null"
      :body-style="{ paddingBottom: '8px' }"
      @cancel="onPickCancel"
    >
      <p class="muted">
        <template v-if="pickTarget === 'create'">
          挂载<b>不复制</b>任何行：产品以后新增子菜单，侧栏下次刷新就跟着多一条。
          勾一个目录 = 把这一支整体挂上去（子树跟着进来）。
        </template>
        <template v-else-if="pickTarget === 'mounted'">
          这一条会记成「挂载：产品 + 清单里的节点 id」，路径与权限词都跟随产品。
          选一个目录 = 挂载这一支（它的子菜单在侧栏里展开）。
        </template>
        <template v-else>
          菜单名、图标、权限词、路径都按产品自己的说法预填，下一步还能改（<b>路径除外</b> ——
          它是那个页面在子应用里的真实路由，手改出来的点进去就是 404）。
        </template>
      </p>
      <a-form layout="vertical">
        <a-form-item v-if="pickTarget === 'create'" label="归属壳">
          <a-radio-group v-model:value="pickScope">
            <a-radio-button value="workbench">工作台壳</a-radio-button>
            <a-radio-button value="project">项目壳</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="产品">
          <a-select
            v-model:value="pickProduct"
            style="max-width: 260px"
            :options="productChoices"
            @change="loadPickMenus"
          />
        </a-form-item>
      </a-form>

      <a-spin :spinning="pickLoading">
        <a-alert v-if="pickError" type="warning" show-icon :message="pickError" style="margin-bottom: 12px" />
        <a-tree
          v-if="!pickError && pickTree.length"
          :tree-data="pickTree"
          :selectable="true"
          default-expand-all
          @select="onPickSelect"
        />
        <p v-if="!pickLoading && !pickError && !pickTree.length" class="muted">
          这个产品没有上报任何页面。
        </p>
      </a-spin>
    </a-modal>

    <a-modal
      v-model:open="open"
      :title="form.id ? '编辑菜单' : '新增菜单'"
      ok-text="保存"
      :confirm-loading="busy"
      @ok="submit"
    >
      <a-form layout="vertical">
        <a-form-item label="归属壳" required>
          <a-radio-group v-model:value="form.scope" :disabled="Boolean(form.id)">
            <a-radio-button value="workbench">工作台壳</a-radio-button>
            <a-radio-button value="project">项目壳</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">
            工作台 = 进项目<b>之前</b>那一级；项目 = 进项目<b>之后</b>。同一棵树在两个壳里各拉一支，
            建好之后不能改（改壳等于换了一棵树）。
          </p>
        </a-form-item>

        <a-form-item label="父节点">
          <a-tree-select
            v-model:value="form.parentId"
            :tree-data="parentTree"
            allow-clear
            placeholder="顶层（主菜单）"
            style="width: 100%"
            :dropdown-style="{ maxHeight: '320px', overflow: 'auto' }"
          />
          <p class="muted" style="margin: 4px 0 0">
            留空 = 这个壳的<b>主菜单</b>（侧栏第一层）。选一个节点 = 成为它的子菜单，层级不限。
          </p>
        </a-form-item>

        <a-form-item label="来源">
          <a-radio-group v-model:value="form.source">
            <a-radio-button value="org">org 自己的页面</a-radio-button>
            <a-radio-button value="manual">手工复制产品页面</a-radio-button>
            <a-radio-button value="mounted" :disabled="!hasProducts">挂载产品节点</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">
            <template v-if="form.source === 'org'">
              路径是本平台的完整路由（如 <code>/org/workbench/projects</code>）。判权词走<b>本项目下
              仓建设</b>的角色（如 <code>iam:member</code>），不满足时这一条在侧栏里<b>置灰</b>而不是消失。
            </template>
            <template v-else-if="form.source === 'manual'">
              把产品清单里的某一条抄成一行。产品以后改了名字、换了路径，这里<b>不跟着变</b>；
              想跟着变请改用「挂载」。
            </template>
            <template v-else>
              只记「产品 + 清单里的节点 id」。路径与权限词都<b>实时</b>取自产品清单 ——
              产品改了这里就跟着改，产品加子菜单侧栏自动多一条。
            </template>
          </p>
        </a-form-item>

        <a-form-item v-if="form.source !== 'org'" label="产品" required>
          <a-select
            v-model:value="form.product"
            :options="productChoices"
            :disabled="form.source === 'mounted'"
          />
          <p class="muted" style="margin: 4px 0 0">
            只列已登记页面地址的产品 —— 没登记的产品配了菜单也点不开。
          </p>
        </a-form-item>

        <a-form-item v-if="form.source === 'mounted'" label="挂载的产品节点" required>
          <a-button @click="openPicker('mounted')">
            {{ form.ref ? '重新选择' : '选择节点' }}
          </a-button>
          <span v-if="form.ref" class="muted" style="margin-left: 8px">
            <code>{{ form.ref }}</code>
          </span>
          <p class="muted" style="margin: 4px 0 0">
            挂的是产品清单里的一个节点（可以是目录），保存时后端会去清单里核对它还在不在。
          </p>
        </a-form-item>

        <!--
          不加 `&& form.product`：选择器自己带一个产品下拉（选完会把产品回填到上面那一项），
          强制「先在表单里选产品才能挑」等于让同一次选择做两遍 —— 而且没有已选产品时
          `openPicker` 会自动落到第一个可用产品上，本来就没有「不知道拉哪个产品的清单」的问题。
        -->
        <a-form-item v-else-if="form.source === 'manual'" label="从产品清单里取">
          <a-button @click="openPicker('manual')">挑一条</a-button>
          <p class="muted" style="margin: 4px 0 0">
            产品报出来的每一条都带着菜单名、图标、权限词与真实路径 —— 挑一条比手抄一遍可靠，
            手抄错的后果（404 或永远判否）当时都不报错。
          </p>
        </a-form-item>

        <a-form-item label="菜单名" required>
          <a-input v-model:value="form.title" placeholder="数据地图" />
        </a-form-item>

        <a-form-item v-if="form.source !== 'mounted'" label="这是个目录（只用来放子菜单）">
          <a-switch v-model:checked="form.isDir" />
          <p class="muted" style="margin: 4px 0 0">
            开 = 这一行自己点不开，只是子菜单的容器（侧栏里是个标题/可折叠的父项）。
          </p>
        </a-form-item>

        <a-form-item v-if="form.source !== 'mounted' && !form.isDir" label="路径" required>
          <!--
            必须写 `:readonly`（全小写）：antd-vue 声明的是 `readonly` 这个 prop，写成
            `:read-only` 会驼峰化成 `readOnly` 对不上，结果渲染出一个非标准的 dead 属性
            `read-only="true"` —— 看着锁了、帮助文案也写着只读，但能打字。
          -->
          <a-auto-complete
            v-if="form.source === 'org'"
            v-model:value="form.path"
            :readonly="pathLocked"
            :options="orgPathOptions"
            placeholder="/org/workbench/projects"
            style="width: 100%"
          />
          <a-input v-else v-model:value="form.path" :readonly="pathLocked" placeholder="/lineage/tables" />
          <p v-if="pathLocked" class="muted" style="margin: 4px 0 0">
            路径是<b>这个页面在子应用里的真实路由</b>，刚才从产品清单里取来的，所以只读。
            要挂一个产品没上报的页面，请改成「org 自己的页面」或先手填。
          </p>
          <p v-else-if="form.source === 'org'" class="muted" style="margin: 4px 0 0">
            本平台的完整路由，可以直接从下拉里选一个现成的页面（也可以手填带
            <code>{code}</code> 占位符的地址 —— 项目壳里的「成员管理」就是这么配的）。
          </p>
          <p v-else class="muted" style="margin: 4px 0 0">
            该产品里的页面路径。填 <code>/</code> 表示进它的首页。
          </p>
        </a-form-item>

        <a-form-item label="权限词">
          <PermSelect v-model:value="form.perm" :options="optionsOf(form.product)" placeholder="catalog:read" />
          <p class="muted" style="margin: 4px 0 0">
            决定<b>谁看得见这个入口</b>：词表由该产品自己上报，所以这里选不到产品不认识的词。
            「不判权」= 进得来就看得见。目录上也可以挂词 —— 整支一起判。
          </p>
          <p v-if="form.product && !optionsOf(form.product).length" class="muted" style="margin: 4px 0 0">
            没拿到「{{ productLabel(form.product) }}」的词表（服务没在运行 / 还没构建过前端 / 「服务注册」
            里没登记页面地址），暂时可以手填。格式是 <code>域:动作</code>，保存时后端会校验。
          </p>
          <p v-else-if="form.source === 'org'" class="muted" style="margin: 4px 0 0">
            org 自有菜单的词表没处上报，直接手填。常见的：<code>iam:member</code>（成员管理）。
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
          <p class="muted" style="margin: 4px 0 0">数字小的排前面。同一层里比大小。</p>
        </a-form-item>

        <a-form-item v-if="form.isDir" label="空目录策略">
          <a-radio-group v-model:value="form.emptyPolicy">
            <a-radio-button value="hide">整支隐藏</a-radio-button>
            <a-radio-button value="always">保留并置灰</a-radio-button>
          </a-radio-group>
          <p class="muted" style="margin: 4px 0 0">
            这一支在当前人那儿<b>一个可用入口都没有</b>时（模块没开通、没派角色、产品还没报出内容）怎么办。
            <br />
            按规范，<b>「数据地图」这一支要选「保留并置灰」</b>：它要求未开通或未派角色时入口仍在、禁用并说明。
          </p>
        </a-form-item>

        <a-form-item v-if="form.source === 'org'" label="仅在租户管理员可见">
          <a-switch v-model:checked="form.adminOnly" />
          <p class="muted" style="margin: 4px 0 0">
            开 = 只有租户管理员看得见（如「用户管理」「设置」这些）。只对 org 自己的节点有意义。
          </p>
        </a-form-item>

        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
          <p class="muted" style="margin: 4px 0 0">
            停用 = 这一支连同子菜单都不出现在侧栏里（子菜单仍在表里，随时可以再启用）。
          </p>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Modal, message } from 'ant-design-vue';
import {
  api,
  type MenuCandidate,
  type MenuCandidatesOfProduct,
  type NavGroupEmptyPolicy,
  type NavNodeRow,
  type NavScope,
  type PermOption,
} from '../../api/client';
import { ORG_PAGES } from '../../config/pages';
import { productLabel } from '../../config/products';
import { navIcons } from '../../config/navIcons';
import PageHeader from '../../components/PageHeader.vue';
import PermSelect from '../../components/PermSelect.vue';

/** 菜单表的**全树顶层**（服务端返回嵌套树，`children` 是真层级）。 */
const rows = ref<NavNodeRow[]>([]);
/** 已登记了前端地址的产品码 —— 只有它们能被挂载 / 从清单取。 */
const configuredProducts = ref<string[]>([]);
const icons = navIcons;
const iconNames = Object.keys(navIcons).sort();

const hasProducts = computed(() => configuredProducts.value.length > 0);
const scopeFilter = ref<'' | NavScope>('');
const scopeChoices = [
  { value: 'workbench', label: '工作台壳' },
  { value: 'project', label: '项目壳' },
];
const productChoices = computed(() =>
  configuredProducts.value.map((p) => ({ value: p, label: productLabel(p) }))
);

function scopeLabel(scope: NavScope) {
  return scope === 'project' ? '项目壳' : '工作台壳';
}

/**
 * 合成的**壳根**：不是菜单表里的行，只是让树在「全部」视图下有个顶层分组。
 *
 * <p>字段填成与服务端行同形（而不是少几个字段）是为了让模板里各处 `record.xxx` 不必
 * 到处判空 —— 壳根唯一可靠的判据就是 id 前缀（见 `isShell`）。
 */
function shellRow(scope: NavScope, children: NavNodeRow[]): NavNodeRow {
  return {
    id: `~${scope}`,
    scope,
    parentId: '',
    label: scopeLabel(scope),
    path: '',
    icon: '',
    perm: '',
    sortOrder: 0,
    enabled: true,
    adminOnly: false,
    product: '',
    ref: '',
    mounted: false,
    emptyPolicy: 'hide',
    children,
  };
}

function isShell(row: NavNodeRow) {
  return row.id.startsWith('~');
}

const tableRows = computed<NavNodeRow[]>(() => {
  const out: NavNodeRow[] = [];
  for (const scope of ['workbench', 'project'] as NavScope[]) {
    if (scopeFilter.value && scopeFilter.value !== scope) continue;
    out.push(shellRow(scope, rows.value.filter((r) => r.scope === scope)));
  }
  return out;
});

/** 展开态：加载后整棵树展开一次（默认收起会让人以为菜单没了好几层）。 */
const expandedKeys = ref<(string | number)[]>([]);

function collectKeys(list: NavNodeRow[]): string[] {
  const out: string[] = [];
  const walk = (nodes: NavNodeRow[]) => {
    for (const n of nodes) {
      if (n.children?.length) {
        out.push(n.id);
        walk(n.children);
      }
    }
  };
  walk(list);
  return out;
}

const cols = [
  { title: '菜单', key: 'name' },
  { title: '路径 / 内容来源', key: 'path' },
  { title: '权限词', key: 'perm', width: 150 },
  { title: '图标', key: 'icon', width: 70 },
  { title: '排序', key: 'sortOrder', width: 66 },
  { title: '空目录', key: 'emptyPolicy', width: 100 },
  { title: '', key: 'act', width: 230 },
];

function rowClass(row: NavNodeRow) {
  if (isShell(row)) return 'nav-branch-row';
  return '';
}

/** 目录 = 自己点不开、只放子菜单。挂载行的这个性质由产品清单里的那个节点决定。 */
function isDir(row: NavNodeRow) {
  if (row.mounted) {
    const node = candNode(row.product, row.ref);
    return node ? !node.path : false;
  }
  return !row.path;
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

// ---- 候选清单（挂载失配判断、可挂载项、批量导入共用一份） ----
const candidates = ref<MenuCandidatesOfProduct[]>([]);
const candLoading = ref(false);

const failedReports = computed(() => candidates.value.filter((c) => !c.ok));

async function refreshCandidates() {
  candLoading.value = true;
  try {
    candidates.value = (await api.platform.navCandidates()).products;
  } catch {
    // 拉不到就当「没有清单」：失配判断会退化成「无从判断」（不标红），
    // 页面上的 failedReports 也不会说假话 —— 它读的是同一份 candidates。
  } finally {
    candLoading.value = false;
  }
}

/** 候选节点索引：`产品 → (节点 id → 节点)`。挂载行的失配判断与目录判断都查它。 */
const candIndex = computed(() => {
  const byProduct = new Map<string, Map<string, MenuCandidate>>();
  for (const p of candidates.value) {
    if (!p.ok) continue;
    const index = new Map<string, MenuCandidate>();
    const walk = (list: MenuCandidate[]) => {
      for (const m of list) {
        index.set(m.id, m);
        walk(m.children ?? []);
      }
    };
    walk(p.menus);
    byProduct.set(p.product, index);
  }
  return byProduct;
});

function candNode(product: string, id: string): MenuCandidate | undefined {
  return candIndex.value.get(product)?.get(id);
}

/**
 * 挂载行失配了吗（产品清单里找不到这个 `ref`）。
 *
 * <p>只在该产品的清单**拉到了**的时候才下结论 —— 拉不到是「无从判断」，标成失配是撒谎。
 */
function isMismatch(row: NavNodeRow) {
  if (!row.mounted) return false;
  const index = candIndex.value.get(row.product);
  return !!index && !index.has(row.ref);
}

function mountedTip(row: NavNodeRow) {
  return `挂载：内容按「${productLabel(row.product)}」自报的清单实时取（节点 ${row.ref}）。
产品改了名字、路径、子菜单，这里会跟着变 —— 不需要任何管理动作。
取消挂载等于删掉这一行（它的手工子菜单会一起没）。`;
}

function mismatchTip(row: NavNodeRow) {
  return `当前能拉到「${productLabel(row.product)}」的清单，但里面没有 id 为 ${row.ref} 的节点 ——
侧栏里这一支会是空的（按空目录策略隐藏或置灰）。多半是产品侧改了节点 id 的生成规则；
在产品侧改回来，或把这一行删掉重挂。`;
}

// ---- 表单 ----
const open = ref(false);
const busy = ref(false);
/**
 * 路径是否只读：从产品清单里挑了一条之后只读。
 *
 * <p>那条路径是**页面在子应用里的真实路由**，手改出来的点进去就是 404；而管理员可能
 * 只是想改个名字 —— 所以锁的是路径这一项，不是整个表单。编辑已有行时**不锁**：
 * 产品换了路由后管理员要能回来同步。
 */
const pathLocked = ref(false);
const form = reactive({
  id: '',
  scope: 'workbench' as NavScope,
  parentId: '',
  source: 'org' as 'org' | 'manual' | 'mounted',
  product: '',
  ref: '',
  title: '',
  isDir: false,
  path: '',
  perm: '',
  icon: '',
  sortOrder: 0,
  enabled: true,
  adminOnly: false,
  emptyPolicy: 'hide' as NavGroupEmptyPolicy,
});

/** 图标下拉的候选 = 本平台收录的图标 ∪ **当前值**（产品可能报了个没收录的图标名）。 */
const iconChoices = computed(() => {
  const names = new Set(iconNames);
  if (form.icon) names.add(form.icon);
  return [...names].sort();
});

/** org 自有页面的路径建议：本平台现成的那些页面（可以手填，所以是 auto-complete）。 */
const orgPathOptions = Object.values(ORG_PAGES).map((p) => ({ value: p }));

/**
 * 「父节点」选择器的树：当前壳的节点（编辑时排除自己 —— 选自己会成环）。
 * 排除一个节点，它的子树自然也跟着不出现。
 */
const parentTree = computed(() => {
  const editId = form.id;
  const walk = (list: NavNodeRow[]): Record<string, unknown>[] =>
    list
      .filter((n) => n.id !== editId)
      .map((n) => {
        const kids = walk(n.children ?? []);
        return {
          value: n.id,
          title: n.label + (isDir(n) ? '（目录）' : ''),
          ...(kids.length ? { children: kids } : {}),
        };
      });
  return walk(rows.value.filter((r) => r.scope === form.scope));
});

function openCreate(parent: NavNodeRow | null) {
  const scope = parent && !isShell(parent) ? parent.scope : scopeFilter.value || 'workbench';
  Object.assign(form, {
    id: '',
    scope,
    parentId: parent && !isShell(parent) ? parent.id : '',
    source: 'org',
    product: '',
    ref: '',
    title: '',
    isDir: false,
    path: '',
    perm: '',
    icon: '',
    sortOrder: 0,
    enabled: true,
    adminOnly: false,
    emptyPolicy: 'hide',
  });
  pathLocked.value = false;
  open.value = true;
}

function openEdit(row: NavNodeRow) {
  Object.assign(form, {
    id: row.id,
    scope: row.scope,
    parentId: row.parentId ?? '',
    source: row.mounted ? 'mounted' : row.product ? 'manual' : 'org',
    product: row.product,
    ref: row.ref,
    title: row.label,
    isDir: isDir(row),
    path: row.path,
    perm: row.perm,
    icon: row.icon,
    sortOrder: row.sortOrder,
    enabled: row.enabled,
    adminOnly: row.adminOnly,
    emptyPolicy: row.emptyPolicy ?? 'hide',
  });
  pathLocked.value = false;
  void ensurePermOptions(row.product);
  open.value = true;
}

// 切换来源时解锁路径：只读是「这一条刚从清单里取来」的临时状态，换了来源就作废。
// 不解锁的话，从「手工复制」切回「org 自己的页面」后路径框还是打不进字。
//
// `flush: 'sync'`：`onPickSelect` 里是「先设 source，紧接着把 pathLocked 置真」，
// 默认的 pre-flush 让这个回调在下一个 tick 才跑，会把刚置上的只读又抹掉
// （表现是「从清单里挑了一条，路径居然还能改」）。
watch(
  () => form.source,
  () => {
    pathLocked.value = false;
  },
  { flush: 'sync' }
);

/** 手工行的路径不允许留空（目录才会空），保存时补一个 `/` 兜底。 */
async function submit() {
  if (!form.title.trim()) {
    message.warning('请填写菜单名');
    return;
  }
  if (form.source !== 'org' && !form.product) {
    message.warning('请选择产品');
    return;
  }
  if (form.source === 'mounted' && !form.ref) {
    message.warning('请选择要挂载的产品节点');
    return;
  }
  if (form.source !== 'mounted' && !form.isDir && !form.path.trim()) {
    message.warning('请填写路径，或把这一条设成目录');
    return;
  }
  busy.value = true;
  const body = {
    scope: form.scope,
    parentId: form.parentId || '',
    title: form.title.trim(),
    // 目录 = 空路径（服务端就是这么判的）；挂载行的路径由产品清单决定，不传。
    path: form.source === 'mounted' || form.isDir ? '' : form.path.trim() || '/',
    perm: form.perm.trim(),
    icon: form.icon || '',
    sortOrder: form.sortOrder ?? 0,
    enabled: form.enabled,
    adminOnly: form.source === 'org' ? form.adminOnly : false,
    product: form.source === 'org' ? '' : form.product,
    ref: form.source === 'mounted' ? form.ref : '',
    mounted: form.source === 'mounted',
    emptyPolicy: form.emptyPolicy,
  };
  try {
    if (form.id) await api.platform.updateNavNode(form.id, body);
    else await api.platform.createNavNode(body);
    open.value = false;
    message.success('已保存');
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

/**
 * 删除（连同整棵子树）。先弹一次确认 —— 服务端删的是整支，删错了没法撤销。
 *
 * <p>确认框里报出**本地算的**子孙条数；服务端返回的 `subtree` 是权威值，两者不一致时
 * 以服务端为准（并发下别人可能刚往这支里加了子菜单），所以删除后照常刷新。
 */
function remove(row: NavNodeRow) {
  const kids = countSubtree(row) - 1;
  Modal.confirm({
    title: `删除「${row.label}」？`,
    content: kids
      ? `这一支下面还有 ${kids} 个子菜单，会一起删掉。`
      : '这一条没有子菜单。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await api.platform.deleteNavNode(row.id);
        await load();
      } catch (e) {
        message.error(e instanceof Error ? e.message : String(e));
      }
    },
  });
}

function countSubtree(row: NavNodeRow): number {
  return 1 + (row.children ?? []).reduce((n, k) => n + countSubtree(k), 0);
}

// ---- 产品节点选择器（挂载 / 从产品清单挑一条） ----
const pickOpen = ref(false);
/** `create` = 选完直接建挂载行；`mounted` = 回填表单的挂载字段；`manual` = 回填手工复制的字段。 */
const pickTarget = ref<'create' | 'mounted' | 'manual'>('create');
const pickScope = ref<NavScope>('workbench');
const pickProduct = ref('');
const pickLoading = ref(false);
const pickTree = ref<Record<string, unknown>[]>([]);
const pickError = ref('');

/**
 * 收起选择器。
 *
 * <p>从表单里点进来的（`mounted` / `manual`）取消后要**回到表单** —— 否则管理员填了一半的
 * 那一屏就凭空没了。工具栏上那个入口（`create`）背后没有表单可回。
 */
function onPickCancel() {
  if (pickTarget.value !== 'create') open.value = true;
}

function openPicker(target: 'create' | 'mounted' | 'manual') {
  pickTarget.value = target;
  pickScope.value = form.scope;
  pickProduct.value = form.product || configuredProducts.value[0] || '';
  pickTree.value = [];
  pickError.value = '';
  // 先收掉表单那一屏：两个弹窗叠着时，后开的这一个会被前一个的遮罩盖住
  // （antd 的 z-index 由它自己管，靠调数字硬压过去是治标）。选完或取消后表单会重开。
  open.value = false;
  pickOpen.value = true;
  if (pickProduct.value) void loadPickMenus();
}

async function loadPickMenus() {
  if (!pickProduct.value) return;
  pickLoading.value = true;
  pickError.value = '';
  pickTree.value = [];
  void ensurePermOptions(pickProduct.value);
  try {
    const res = await api.platform.navCandidates();
    candidates.value = res.products;
    const found = res.products.find((p) => p.product === pickProduct.value);
    if (!found) {
      // 「服务注册」里没有这个产品：说清去哪儿补，而不是只显示一个空列表
      pickError.value = `「服务注册」里没有登记 ${productLabel(pickProduct.value)} 的页面地址，没法从它取候选`;
    } else if (!found.ok) {
      pickError.value = found.error;
    } else {
      pickTree.value = toPickTree(found.menus);
    }
  } catch (e) {
    pickError.value = e instanceof Error ? e.message : String(e);
  } finally {
    pickLoading.value = false;
  }
}

function toPickTree(list: MenuCandidate[]): Record<string, unknown>[] {
  return list.map((m) => {
    const kids = toPickTree(m.children ?? []);
    return {
      key: m.id,
      title: `${m.label}${m.path ? `（${m.path}）` : '（目录）'}`,
      ...(kids.length ? { children: kids } : {}),
    };
  });
}

/** 点一个节点：直接建挂载行，或回填表单（挂载 / 手工复制两种来源）。 */
async function onPickSelect(keys: (string | number)[]) {
  const id = String(keys[0] ?? '');
  if (!id) return;
  const node = candNode(pickProduct.value, id);
  if (!node) return;

  if (pickTarget.value === 'mounted' || pickTarget.value === 'manual') {
    form.source = pickTarget.value;
    // 归属壳也按产品的建议值填上（它只是建议，管理员照样能改 —— 同一条路径
    // 挂到两个壳上是合法用法）
    form.scope = node.scope;
    form.product = pickProduct.value;
    form.ref = pickTarget.value === 'mounted' ? id : '';
    form.isDir = !node.path;
    // 名字/图标/权限词按产品的说法预填，管理员还能改
    if (!form.title.trim()) form.title = node.label;
    if (!form.icon) form.icon = node.icon;
    if (!form.perm.trim()) form.perm = node.perm;
    if (pickTarget.value === 'manual') {
      // 手工复制要落一个真实路径 —— 它现在就是产品报的那条，所以锁住（见表单里的说明）
      form.path = node.path;
      pathLocked.value = true;
    }
    pickOpen.value = false;
    open.value = true;
    return;
  }

  pickOpen.value = false;
  busy.value = true;
  try {
    await api.platform.createNavNode({
      scope: pickScope.value,
      parentId: '',
      title: node.label,
      path: '',
      perm: node.perm,
      icon: node.icon,
      sortOrder: node.sort,
      enabled: true,
      product: pickProduct.value,
      ref: id,
      mounted: true,
      emptyPolicy: 'hide',
    });
    message.success('已挂载');
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

// ---- 从服务拉取（批量导入为手工行） ----
const fetchOpen = ref(false);
const fetching = ref(false);
const submitting = ref(false);
const checkedKeys = ref<(string | number)[]>([]);
/** 勾选的 key 集合（`产品 + 节点 id`）。半选的父节点不进来 —— 它们由「透明层」逻辑兜住。 */
const picked = ref<Set<string>>(new Set());
const draft = reactive<Record<string, { scope: NavScope; title: string; perm: string; sortOrder: number }>>({});

function candKey(product: string, id: string) {
  return `${product}/${id}`;
}

/** key → 节点（导入时逐个建，需要知道各自的 path 与父子关系）。 */
const candByKey = computed(() => {
  const out = new Map<string, { product: string; node: MenuCandidate }>();
  for (const p of candidates.value) {
    for (const m of flattenCand(p.menus)) out.set(candKey(p.product, m.id), { product: p.product, node: m });
  }
  return out;
});

function flattenCand(list: MenuCandidate[]): MenuCandidate[] {
  const out: MenuCandidate[] = [];
  const walk = (l: MenuCandidate[]) => {
    for (const m of l) {
      out.push(m);
      walk(m.children ?? []);
    }
  };
  walk(list);
  return out;
}

/** 左树：产品 → 该产品自报的菜单树（产品根也 checkable = 整产品导入）。 */
const candTree = computed(() =>
  candidates.value
    .filter((p) => p.ok && p.menus.length)
    .map((p) => ({
      key: `p:${p.product}`,
      title: productLabel(p.product),
      children: toPickTree(p.menus).map((n) => ({ ...n, key: candKey(p.product, String(n.key)) })),
    }))
);

const failedCandidates = computed(() => candidates.value.filter((c) => !c.ok));
const fetchableCount = computed(() => candidates.value.filter((c) => c.ok && c.menus.length).length);

const pickedCols = [
  { title: '归属壳', key: 'scope', width: 116 },
  { title: '菜单名', key: 'title', width: 170 },
  { title: '路径', key: 'path', width: 190 },
  { title: '权限词', key: 'perm', width: 160 },
  { title: '排序', key: 'sort', width: 80 },
  { title: '', key: 'act', width: 76 },
];

const pickedRows = computed(() =>
  [...picked.value]
    .map((key) => {
      const hit = candByKey.value.get(key);
      return hit ? { key, product: hit.product, node: hit.node } : null;
    })
    .filter((r): r is { key: string; product: string; node: MenuCandidate } => !!r)
);

/**
 * 勾选变化的处理。
 *
 * <p>`checkedKeys` 在非严格模式下由 rc-tree 算好（勾父带全子孙、部分勾父进 `halfChecked`），
 * 我们只收**选中的**那些；没选中的中间节点在导入时当「透明层」处理（见 `walkImport`），
 * 所以「只勾孙子、不勾父亲」也能落成一个挂到顶层的孙子。
 */
function onTreeCheck(keys: (string | number)[] | { checked: (string | number)[] }) {
  const checked = Array.isArray(keys) ? keys : keys.checked;
  const next = new Set<string>();
  for (const k of checked) {
    const key = String(k);
    // 产品根节点（`p:xxx`）展开成它的全部节点
    if (key.startsWith('p:')) {
      const product = key.slice(2);
      const found = candidates.value.find((p) => p.product === product);
      for (const m of flattenCand(found?.menus ?? [])) next.add(candKey(product, m.id));
      continue;
    }
    next.add(key);
  }
  // 只为新进来的 key 建草稿：改过的值不该被一次勾选重置
  for (const key of next) {
    if (draft[key]) continue;
    const hit = candByKey.value.get(key);
    if (!hit) continue;
    draft[key] = {
      scope: hit.node.scope,
      title: hit.node.label,
      perm: hit.node.perm,
      sortOrder: hit.node.sort ?? 0,
    };
  }
  picked.value = next;
  checkedKeys.value = checked;
}

function unpick(key: string) {
  const next = new Set(picked.value);
  next.delete(key);
  picked.value = next;
  checkedKeys.value = [...next];
}

async function openFetch() {
  fetchOpen.value = true;
  checkedKeys.value = [];
  picked.value = new Set();
  fetching.value = true;
  try {
    candidates.value = (await api.platform.navCandidates()).products;
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    fetching.value = false;
  }
}

/**
 * 导入：把勾中的候选**抄成手工行**（要「跟着产品变」请用挂载）。
 *
 * <p>逐条建而不是批量端点 —— V23 后端没有批量接口，而嵌套导入本来就需要「父建完拿到
 * id 才能建子」，串行是最直白的写法。失败即中断，已建的留在那儿（管理员看得到、
 * 也能删），不静默回滚 —— 回滚要一堆补偿删除，反而更容易留下半成品。
 */
async function submitBatch() {
  submitting.value = true;
  let created = 0;
  try {
    for (const product of candidates.value.filter((p) => p.ok)) {
      created += await walkImport(product.product, product.menus, '');
    }
    message.success(`已导入 ${created} 项`);
    fetchOpen.value = false;
    await load();
  } catch (e) {
    message.error(`已导入 ${created} 项后中断：${e instanceof Error ? e.message : String(e)}`);
    await load();
  } finally {
    submitting.value = false;
  }
}

async function walkImport(product: string, list: MenuCandidate[], parentId: string): Promise<number> {
  let n = 0;
  for (const node of list) {
    const key = candKey(product, node.id);
    const picked_ = picked.value.has(key);
    if (picked_) {
      const d = draft[key];
      const created = await api.platform.createNavNode({
        scope: d?.scope ?? node.scope,
        parentId,
        title: (d?.title ?? node.label).trim() || node.label,
        path: node.path,
        perm: d?.perm ?? node.perm,
        icon: node.icon,
        sortOrder: d?.sortOrder ?? node.sort ?? 0,
        enabled: true,
        product,
        mounted: false,
        emptyPolicy: 'hide',
      });
      n += 1;
      // 子节点挂到**刚建出来的这一行**下（产品的层级照搬）
      n += await walkImport(product, node.children ?? [], created.id);
    } else {
      // 没选中：当透明层，子节点上提到当前父节点（「只勾孙子不勾父亲」也能落下来）
      n += await walkImport(product, node.children ?? [], parentId);
    }
  }
  return n;
}

// ---- 加载 ----
async function load() {
  try {
    const [nodes, services] = await Promise.all([api.platform.navNodes(), api.platform.services()]);
    rows.value = nodes;
    configuredProducts.value = services.filter((s) => s.frontendUrl).map((s) => s.product);
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
    return;
  }
  // 展开一次整棵树（过滤后仍保留已展开的壳）
  expandedKeys.value = [...collectKeys(rows.value), ...tableRows.value.map((r) => r.id)];
  // 有挂载行时才去拉产品清单：一份挂载都没有时「失配」不可能发生，
  // 没必要为此在每次打开这一页时都去打一圈产品（某个产品挂了就是一次次等满超时）。
  if (rows.value.some((r) => r.mounted) || hasProducts.value) await refreshCandidates();
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
/* 壳根行与叶子区分开：层次靠缩进，身份靠底色与字重 */
:deep(.nav-branch-row) > td {
  background: #f2f3f5;
  font-weight: 600;
}
:deep(.nav-branch-row:hover) > td {
  background: #e9ebee;
}
/* 目录：字重略重 + 灰一点，与「点得开的叶子」区分 */
.node-dir {
  color: rgba(0, 0, 0, 0.72);
  font-weight: 500;
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
