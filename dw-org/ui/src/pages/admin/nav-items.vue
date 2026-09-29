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
        <a-button type="primary" @click="openCreate(null)">新增菜单</a-button>
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

    <!-- 排序的口径必须写在这儿。两件事不写下来就会被当成 bug 报回来：
         ① 那一列显示的不是库里的数字（原先并排显示各层的 sortOrder，跨层不可比）；
         ② 位次是**相对位置**，同层增删一条，后面的位次会跟着整体移动。 -->
    <p class="muted sort-note">
      「排序」列显示的是<b>分层位次</b>（<code>2.3</code> = 第 2 支下的第 3 个），
      悬停能看到它在第几层、以及库里那个原始排序值。新建的菜单落在<b>本层末尾</b>，
      顺序用行内的 ↑↓ 调 —— 调的是它在本层的位置，所以同层增删一条时，后面的位次会跟着变。
    </p>

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
            <a-tag v-if="record.entryPage" color="geekblue" style="margin-left: 6px">目录页</a-tag>
            <a-tag v-if="record.externalUrl" color="green" style="margin-left: 6px">外链</a-tag>
            <a-tooltip v-if="record.mounted" :title="mountedTip(record)">
              <a-tag color="cyan" style="margin-left: 6px">挂载</a-tag>
            </a-tooltip>
            <a-tooltip v-if="isMismatch(record)" :title="mismatchTip(record)">
              <a-tag color="orange" style="margin-left: 6px">已失配</a-tag>
            </a-tooltip>
            <a-tooltip v-if="isVirtual(record)" :title="virtualTip(record)">
              <a-tag color="default" style="margin-left: 6px">产品清单</a-tag>
            </a-tooltip>
            <!-- 挂载行的子菜单只能来自产品（后端 `renderMounted` 不读 org 子行），
                 所以下面这几条是**永远不会显示**的 —— 摆在这儿是为了能看见、能删掉，
                 行内也必须说清它们不生效，否则就是一次静默失效。 -->
            <a-tooltip v-if="record.mounted && staleKidCount(record)" :title="staleKidTip(record)">
              <a-tag color="orange" style="margin-left: 6px">
                {{ staleKidCount(record) }} 条子菜单不显示
              </a-tag>
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
            <!-- 目录页与外链的 path 是**服务端写的模板**（`{node}` 由前端换成节点 id），
                 原样显示没有意义 —— 管理员既没填过它，也不该改它。 -->
            <span v-else-if="record.entryPage" class="muted">
              Tab 页，挂了 {{ entryPickCount(record) }} 个菜单
            </span>
            <template v-else-if="record.externalUrl">
              <code>{{ record.externalUrl }}</code>
              <span class="muted" style="margin-left: 6px">
                {{ record.openMode === 'embed' ? '内嵌' : '新标签页' }}
                <template v-if="record.authMode === 'token'">
                  · token {{ record.hasToken ? '已配' : '未配' }}
                </template>
                <template v-else-if="record.authMode === 'basic'">
                  · 账号 {{ record.hasPassword ? '已配' : '未配' }}
                </template>
              </span>
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
          <span v-if="isVirtual(record)" class="muted">随产品</span>
          <!-- 显示的是**沿树合成出来的分层位次路径**（`2.3` = 顶层第 2 支下的第 3 个），
               不是库里的 `sortOrder`。后者只在本层有意义（见 `NavNodeEntity` 的注释），
               而表格把整棵树摊平，各层的数字并排显示在同一列时会被读成一个跨层的全局序列
               ——「排序看着乱」就是这么来的。层数与原始值进 tooltip，排查时看得见。 -->
          <span v-else-if="!isShell(record)" class="cell-sort">
            <a-tooltip :title="sortTip(record)">
              <span class="sort-idx">{{ pathIndex(record) }}</span>
            </a-tooltip>
            <span class="sort-move">
              <a-button
                size="small"
                type="text"
                :disabled="!!moving || !canMove(record, -1)"
                :loading="moving?.id === record.id && moving?.delta === -1"
                @click="move(record, -1)"
              >
                ↑
              </a-button>
              <a-button
                size="small"
                type="text"
                :disabled="!!moving || !canMove(record, 1)"
                :loading="moving?.id === record.id && moving?.delta === 1"
                @click="move(record, 1)"
              >
                ↓
              </a-button>
            </span>
          </span>
        </template>

        <template v-else-if="column.key === 'emptyPolicy'">
          <!-- 产品子节点的空目录行为由产品那一套渲染决定，org 侧这一列对它不生效 -->
          <span v-if="isVirtual(record)" class="muted">随产品</span>
          <template v-else-if="!isShell(record) && isDir(record)">
            <a-tag :color="record.emptyPolicy === 'always' ? 'orange' : 'default'">
              {{ record.emptyPolicy === 'always' ? '保留并置灰' : '隐藏' }}
            </a-tag>
          </template>
        </template>

        <template v-else-if="column.key === 'act'">
          <span v-if="isVirtual(record)" class="muted">随产品清单，改不了</span>
          <!-- 挂载行不给「新增子菜单」：加出来的行在侧栏里不会显示（见 `staleKidTip`）。
               不是把按钮藏掉就完事 —— 说清为什么没有，否则会被当成界面缺了一块。 -->
          <a-tooltip v-else-if="record.mounted" title="挂载行的子菜单由产品清单决定，这里加的子行在侧栏里不会显示">
            <span class="muted">子菜单随产品</span>
          </a-tooltip>
          <!-- 壳根行上也是「新增菜单」：同一个动作在工具栏和行内叫两个名字，只会让人
               以为它们建出来的东西不一样。 -->
          <a-button v-else size="small" type="link" @click="openCreate(record)">
            {{ isShell(record) ? '新增菜单' : '新增子菜单' }}
          </a-button>
          <template v-if="!isShell(record) && !isVirtual(record)">
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <!-- 从产品来的（挂载 / 手工复制）才给删除：它们的定义在产品那边，删了能再挂回来。 -->
            <a-button v-if="canDelete(record)" size="small" danger @click="remove(record)">删除</a-button>
            <!--
              org 自己的（页面 / 目录 / 入口页 / 外链）删了找不回来，只给停用。

              写成**禁用说明**而不是「干脆不画这个按钮」：把按钮藏掉，管理员会以为界面坏了
              （「别人那行有删除、我这行没有」），而他真正需要知道的是「不是不能收起来，
              是用『停用』收」。所以按钮照给，只是换成那个可逆的动作。
            -->
            <a-tooltip v-else title="org 自己的菜单删了找不回来，只能停用">
              <a-button size="small" @click="toggleEnabled(record)">
                {{ record.enabled ? '停用' : '启用' }}
              </a-button>
            </a-tooltip>
          </template>
        </template>
      </template>
    </a-table>

    <p v-if="rows.length" class="muted">
      <b>两类节点的区别</b>：org 自己的页面（路径形如 <code>/org/...</code>，直接跳）与从产品挂上来的页面。
      产品那一类又分两种：<b>手工复制</b>（把产品清单里的某一条抄成一行，产品以后改了这里不跟着变）与
      <b>挂载</b>（只记「产品 + 清单里的节点 id」，内容是每次渲染时现取的 —— 产品新增子菜单，侧栏自动跟上）。
      <b>两类能做的操作也不同</b>：产品那一类可以<b>删除</b>（它的定义在产品那边，删了随时能再挂回来）；
      org 自己的那一类<b>只能停用</b>（页面 / 目录 / 入口页 / 外链都算 —— 它们的地址与凭据是内部知识，
      删掉之后不知道填什么才能恢复）。停用是可逆的：那一支连同子菜单不再出现在侧栏里，
      但这一页仍然列得出来，随时可以再启用。
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
                归属壳、菜单名、权限词都能改了再保存；层级照产品的清单落下来，排序也是
              </span>
            </div>

            <!-- table-layout 必须**显式**给 fixed。只写 scroll.x 是不够的：实测 antd 渲染出来
                 仍是 `table-layout: auto`（它只在这三种情况下才自己切 fixed —— 表头固定
                 scroll.y、有 fixed 列、某列 ellipsis），于是下面 colgroup 里那几列 width
                 只是**建议值**，浏览器按内容重新分配：「路径」列里是不换行的长路径，一路
                 吃掉宽度，把「菜单名」压成几十像素（输入框 min-content 很小，抢不过）。
                 短路径时 auto 恰好也分得开，所以这个问题只在长路径下露头。 -->
            <a-table
              v-if="pickedRows.length"
              :data-source="pickedRows"
              :columns="pickedCols"
              row-key="key"
              size="small"
              :pagination="false"
              table-layout="fixed"
              :scroll="{ x: 768 }"
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
                  <code v-if="record.node.path" class="cell-path" :title="record.node.path">
                    {{ record.node.path }}
                  </code>
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

    <!-- 挂载 / 从产品取：同一个选择器，两种落点（直接建挂载行 / 回填表单）。
         用抽屉而不是弹框：这一屏左树右草稿，内容动辄比视口高，弹框里按钮排在内容末尾，
         得先滚到底才看得见；抽屉的底栏常驻，而且和上面那个「从服务拉取菜单」同形。 -->
    <a-drawer
      v-model:open="pickOpen"
      :title="
        pickTarget === 'create'
          ? '挂载产品菜单'
          : pickTarget === 'mounted'
            ? '选一个要挂载的产品节点'
            : '从产品清单里挑一条'
      "
      placement="right"
      :width="pickTarget === 'create' ? 1040 : 900"
      :body-style="{ paddingBottom: 16 }"
      @close="onPickCancel"
    >
      <!--
        挂载这一档做成**左树右草稿**，照「从服务拉取菜单」那一屏的做法：左边选中一项
        只是把它填进右边那条草稿，名字、权限词、排序、挂到哪一级都能改了再按「挂载」。
        原先点一下树就立刻建行 —— 位置固定是顶层、名字照抄产品，管理员在按下去之前
        看不到任何一项，这就是「选完不知道挂到哪里了」的来历。
      -->
      <template v-if="pickTarget === 'create'">
        <div class="pick-split mount-split">
          <div class="pick-left">
            <p class="muted">
              挂载<b>不复制</b>任何行：产品以后新增子菜单，侧栏下次刷新就跟着多一条。
              选中一个目录 = 把这一支整体挂上去。
            </p>
            <a-form layout="vertical">
              <a-form-item label="归属壳">
                <a-radio-group v-model:value="pickScope" @change="onPickScopeChange">
                  <a-radio-button value="workbench">工作台壳</a-radio-button>
                  <a-radio-button value="project">项目壳</a-radio-button>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="产品">
                <a-select
                  v-model:value="pickProduct"
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
              <p v-else-if="!pickLoading && !pickError" class="muted">这个壳下没有可挂的页面。</p>
            </a-spin>
          </div>

          <div class="pick-right">
            <div class="pick-head">
              <b>{{ pickDraft ? '将挂载 1 项' : '还没选' }}</b>
              <span v-if="pickDraft" class="muted">下面几项都能改了再挂</span>
            </div>
            <a-form v-if="pickDraft" layout="vertical" class="mount-form">
              <a-form-item label="挂到哪个菜单下">
                <a-tree-select
                  v-model:value="pickDraft.parentId"
                  :tree-data="pickParentTree"
                  allow-clear
                  placeholder="顶层（主菜单）"
                  style="width: 100%"
                  :dropdown-style="{ maxHeight: '260px', overflow: 'auto' }"
                />
              </a-form-item>
              <a-form-item label="菜单名">
                <a-input v-model:value="pickDraft.title" :maxlength="64" />
              </a-form-item>
              <a-form-item label="权限词">
                <PermSelect
                  v-model:value="pickDraft.perm"
                  :options="optionsOf(pickProduct)"
                  placeholder="不判权"
                />
              </a-form-item>
              <dl class="mount-src">
                <div>
                  <dt>内容来源</dt>
                  <dd>{{ productLabel(pickProduct) }} · <code>{{ pickDraft.node.id }}</code></dd>
                </div>
                <div>
                  <dt>路径</dt>
                  <dd>
                    <code v-if="pickDraft.node.path">{{ pickDraft.node.path }}</code>
                    <span v-else class="muted">目录（不可点）</span>
                  </dd>
                </div>
              </dl>
              <p class="muted mount-note">
                路径不填也不给改：它按产品清单实时取，手填的对不上产品真实路由，点开就是 404。
              </p>
            </a-form>
            <p v-else class="muted empty-pick">
              在左边选中要挂的菜单。选<b>目录</b>会把这一支整体挂上来，子菜单跟着产品的清单走。
            </p>
          </div>
        </div>
      </template>

      <!-- 另外两档是往表单里回填，没有「确认」这一步（表单本身就是确认），保持原样 -->
      <template v-else>
        <p class="muted">
          <template v-if="pickTarget === 'mounted'">
            这一条会记成「挂载：产品 + 清单里的节点 id」，路径与权限词都跟随产品。
            选一个目录 = 挂载这一支（它的子菜单在侧栏里展开）。
          </template>
          <template v-else>
            菜单名、图标、权限词、路径都按产品自己的说法预填，下一步还能改（<b>路径除外</b> ——
            它是那个页面在子应用里的真实路由，手改出来的点进去就是 404）。
          </template>
        </p>
        <a-form layout="vertical">
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
      </template>

      <!-- 底栏常驻。另外两档点一条树就回填并关掉，没有「确认」这一步，所以只给「取消」；
           不给它们也塞一个主按钮 —— 那会让人以为还要再按一次。 -->
      <template #footer>
        <div class="drawer-foot">
          <span class="muted">
            {{ pickTarget === 'create' && pickDraft ? '挂载不复制行，产品改了这里跟着变' : '' }}
          </span>
          <span>
            <a-button @click="onPickCancel">取消</a-button>
            <a-button
              v-if="pickTarget === 'create'"
              type="primary"
              :disabled="!pickDraft"
              :loading="pickBusy"
              @click="confirmMount"
            >
              挂载
            </a-button>
          </span>
        </div>
      </template>
    </a-drawer>

    <!-- 与另外两屏统一成右侧抽屉。宽度 820 不是抽屉的默认值：目录页的「挂进来的菜单」
         一行里要塞下「挑菜单的下拉 + Tab 名 + 上移/下移/删除」四样，窄了下拉只剩几十像素，
         候选全被截成「规范…」「建模…」——下拉本身再宽也没用，因为 antd 的下拉默认跟着
         select 的宽度走。
         抽屉没有 ok-text/@ok，底栏自绘（与「从服务拉取菜单」同一套 .drawer-foot）。 -->
    <a-drawer
      v-model:open="open"
      :title="form.id ? '编辑菜单' : '新增菜单'"
      placement="right"
      :width="820"
      :body-style="{ paddingBottom: 16 }"
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
            <a-radio-button value="entry">目录页（集合）</a-radio-button>
            <a-radio-button value="external">外链菜单</a-radio-button>
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
            <template v-else-if="form.source === 'mounted'">
              只记「产品 + 清单里的节点 id」。路径与权限词都<b>实时</b>取自产品清单 ——
              产品改了这里就跟着改，产品加子菜单侧栏自动多一条。
            </template>
            <template v-else-if="form.source === 'entry'">
              这一条<b>自己是一个页面</b>：点进去是一排 <b>Tab</b>，挂进来一条菜单就是一个 Tab，
              Tab 里是那条菜单<b>自己的页面</b>（内嵌在框里，不跳走）。
              被挂的菜单<b>在它原来的位置也还在</b>（是引用，不是搬走）—— 同一条菜单可以同时
              出现在多个目录页里。
            </template>
            <template v-else>
              指向平台外面的一个地址（如 <code>https://grafana.example.com</code>）。
              可以内嵌进壳里，也可以新标签页打开。
              <b>外部系统（不是本平台的产品）就用这一档挂</b> —— 它们的菜单是浏览器里跑 JS
              画出来的，服务端拿不到，所以「服务注册」那条路对它们不成立；这里只要一个地址，
              既不查服务注册、也不查菜单清单。想分组就先建一个目录，把外链挂在它下面。
            </template>
          </p>
        </a-form-item>

        <a-form-item v-if="form.source === 'manual' || form.source === 'mounted'" label="产品" required>
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

        <!-- 目录页：把别的菜单挂进来（引用，被挂的那些在原位置也还在） -->
        <a-form-item v-if="form.source === 'entry'" label="挂进来的菜单">
          <div class="link-rows">
            <div v-for="(row, index) in form.linkRows" :key="index" class="link-row">
              <a-select
                v-model:value="row.pick"
                :options="linkOptions"
                placeholder="挑一条菜单"
                show-search
                :filter-option="filterLinkOption"
                class="link-row-pick"
                :popup-match-select-width="false"
                :dropdown-style="{ minWidth: '480px', maxHeight: '320px', overflow: 'auto' }"
              />
              <a-input
                v-model:value="row.label"
                placeholder="Tab 名称（可留空）"
                :maxlength="64"
                class="link-row-label"
              />
              <a-button size="small" :disabled="index === 0" @click="moveLinkRow(index, -1)">
                上移
              </a-button>
              <a-button
                size="small"
                :disabled="index === form.linkRows.length - 1"
                @click="moveLinkRow(index, 1)"
              >
                下移
              </a-button>
              <a-button size="small" danger @click="form.linkRows.splice(index, 1)">删除</a-button>
            </div>
            <a-button size="small" @click="form.linkRows.push({ pick: '', label: '' })">
              添加一行
            </a-button>
          </div>
          <p class="muted" style="margin: 4px 0 0">
            <b>一行一个 Tab，行的顺序就是 Tab 的顺序</b>（用上移 / 下移调）。
            <b>Tab 名称</b>留空就用被挂菜单自己的名字 —— 挂了两条同名的菜单（比如两个「概况」）时，
            给其中一条填个名字才分得出谁是谁。
          </p>
          <p class="muted" style="margin: 4px 0 0">
            两类来源一份列表：<b>本站菜单</b>（同壳、不是目录页）与<b>产品清单里的节点</b>
            （带「· 产品名」后缀）。选一个<b>产品目录</b> = 把这一支整支带进来，里面的每一条子菜单
            各占一个 Tab —— 产品<b>运行期</b>才报上来的子项（如「建模中心」下按项目登记的分层）
            只能这样带出来，它们在候选里是看不到的。
          </p>
          <p class="muted" style="margin: 4px 0 0">
            只列<b>同一个壳</b>里的菜单 —— 跨壳挂会让这一支在侧栏里整片消失，所以服务端也会拒。
            目录页不能再挂另一个目录页（Tab 里再嵌一层 Tab 说不清该显示什么）。
          </p>
        </a-form-item>

        <!-- 外链菜单：地址 + 打开方式 + 认证 + 可见范围 -->
        <template v-if="form.source === 'external'">
          <a-form-item label="外链地址" required>
            <a-input v-model:value="form.externalUrl" placeholder="https://grafana.example.com/d/abc" />
            <p class="muted" style="margin: 4px 0 0">
              只支持 <code>http</code> / <code>https</code>。
            </p>
          </a-form-item>

          <a-form-item label="打开方式">
            <a-radio-group v-model:value="form.openMode">
              <a-radio-button value="embed">内嵌在壳里</a-radio-button>
              <a-radio-button value="jump">新标签页打开</a-radio-button>
            </a-radio-group>
            <p class="muted" style="margin: 4px 0 0">
              内嵌时页面顶部常驻一个「在新标签页打开」按钮 —— 目标站如果禁止被内嵌
              （<code>X-Frame-Options</code> / <code>CSP</code>），浏览器<b>不会</b>把失败告诉页面，
              只能靠那个按钮兜底。
            </p>
          </a-form-item>

          <a-form-item label="认证方式">
            <a-radio-group v-model:value="form.authMode">
              <a-radio-button value="none">不授权</a-radio-button>
              <a-radio-button value="token">token</a-radio-button>
              <a-radio-button value="basic">账号密码</a-radio-button>
            </a-radio-group>
            <p v-if="form.authMode === 'token'" class="muted" style="margin: 4px 0 0">
              token 会被<b>自动拼进地址的查询串</b>（<code>?token=…</code>），内嵌与外跳都带。
              这是浏览器的限制：iframe 不能带自定义请求头，所以只能拼 URL ——
              代价是它会进浏览器历史、目标站的访问日志。
            </p>
            <p v-else-if="form.authMode === 'basic'" class="muted" style="margin: 4px 0 0">
              <b>账号密码不会自动登录</b>：现代浏览器禁止 <code>https://user:pass@host</code>
              作为 iframe 地址，也无法代填第三方的登录表单。这一档的实际用途只有
              「平台管理员保存备查 + 复制」—— <b>使用者拿不到明文</b>。
            </p>
          </a-form-item>

          <a-form-item v-if="form.authMode !== 'none'" label="凭据">
            <div class="cred">
              <template v-if="form.authMode === 'token'">
                <a-input-password
                  v-model:value="form.token"
                  :placeholder="form.hasToken ? '已配置（留空 = 不改）' : '粘贴 token'"
                />
              </template>
              <template v-else>
                <a-input v-model:value="form.basicUser" placeholder="用户名" />
                <a-input-password
                  v-model:value="form.password"
                  :placeholder="form.hasPassword ? '已配置（留空 = 不改）' : '密码'"
                />
              </template>
              <!-- 复制按钮只对**已保存的**行有意义：新填的明文本来就在输入框里 -->
              <a-button v-if="form.id" @click="copyCredential">复制已保存的</a-button>
            </div>
            <p class="muted" style="margin: 4px 0 0">
              留空 = <b>保持原来的值</b>（不是清空）—— 出于安全，已保存的凭据不回显到这里。
              要换就重新填一个。
            </p>
          </a-form-item>

          <a-form-item label="可见范围">
            <a-radio-group v-model:value="form.visibility">
              <a-radio-button value="all">所有人</a-radio-button>
              <a-radio-button value="tenant_admin">仅租户管理员</a-radio-button>
            </a-radio-group>
            <p class="muted" style="margin: 4px 0 0">
              只有两档：外链没有产品，而「指定产品角色」那一类可见范围要 <code>product</code>
              才算得出来。
            </p>
          </a-form-item>
        </template>

        <a-form-item label="菜单名" required>
          <a-input v-model:value="form.title" placeholder="数据地图" />
        </a-form-item>

        <a-form-item
          v-if="form.source === 'org' || form.source === 'manual'"
          label="这是个目录（只用来放子菜单）"
        >
          <a-switch v-model:checked="form.isDir" />
          <p class="muted" style="margin: 4px 0 0">
            开 = 这一行自己点不开，只是子菜单的容器（侧栏里是个标题/可折叠的父项）。
          </p>
        </a-form-item>

        <a-form-item
          v-if="(form.source === 'org' || form.source === 'manual') && !form.isDir"
          label="路径"
          required
        >
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

        <!-- 外链没有权限词：它的门禁是上面那个「可见范围」（角色词要 product 才判得动） -->
        <a-form-item v-if="form.source !== 'external'" label="权限词">
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

        <a-form-item
          v-if="form.source === 'org' || form.source === 'entry'"
          label="仅在租户管理员可见"
        >
          <a-switch v-model:checked="form.adminOnly" />
          <p class="muted" style="margin: 4px 0 0">
            开 = 只有租户管理员看得见（如「用户管理」「设置」这些）。只对 org 自己的节点有意义 ——
            <b>外链不用这个开关</b>，它有自己的「可见范围」。
          </p>
        </a-form-item>

        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" />
          <p class="muted" style="margin: 4px 0 0">
            停用 = 这一支连同子菜单都不出现在侧栏里（子菜单仍在表里，随时可以再启用）。
          </p>
        </a-form-item>
      </a-form>

      <template #footer>
        <div class="drawer-foot">
          <span class="muted">{{ form.id ? '改了保存即生效，侧栏下次刷新跟着变' : '' }}</span>
          <span>
            <a-button @click="open = false">取消</a-button>
            <a-button type="primary" :loading="busy" @click="submit">保存</a-button>
          </span>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Modal, message } from 'ant-design-vue';
import {
  api,
  type MenuCandidate,
  type MenuCandidatesOfProduct,
  type NavEntryLinkReq,
  type NavExternalAuthMode,
  type NavExternalOpenMode,
  type NavExternalVisibility,
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

/** 前端合成的只读行（挂载行下面那棵产品子树），见 {@link productKids}。 */
function isVirtual(row: NavNodeRow) {
  return row.virtual === true;
}

/**
 * 挂载行 → 它下面那棵**产品子树**（前端合成的只读行）。
 *
 * <p>挂载的语义是「不复制任何行」：`nav_nodes` 里只有挂载行自己一行，子菜单由后端
 * `renderMounted` 在渲染时从产品清单拉。于是管理页直接读表时，这一行下面**永远是空的**，
 * 而侧栏里明明有一整棵 —— 两边看到的东西对不上，管理员没法判断自己挂对了没有。
 *
 * <p>清单拉不到（「服务注册」没配地址 / 请求失败）时**不展开**：那时画一棵空的不如
 * 什么都不画，行内已有「已失配」标签与 {@link mountedTip} 交代。
 */
function productKids(row: NavNodeRow): NavNodeRow[] {
  const root = candNode(row.product, row.ref);
  if (!root) return [];
  const conv = (n: MenuCandidate, parentId: string): NavNodeRow => {
    const id = `cand:${row.product}/${n.id}`;
    const kids = (n.children ?? []).map((k) => conv(k, id));
    return {
      id,
      // 壳跟随挂载行。虚拟行不参与任何按壳的过滤，写它自己报的 scope 只会让别处漏掉它。
      scope: row.scope,
      parentId,
      label: n.label,
      path: n.path,
      icon: n.icon,
      perm: n.perm,
      sortOrder: n.sort,
      enabled: true,
      adminOnly: false,
      product: row.product,
      ref: n.id,
      mounted: false,
      emptyPolicy: 'hide',
      virtual: true,
      // 叶子**不带** children（而不是给个空数组）：antd 表格见到空数组也会画一个
      // 展开箭头，点开什么都没有 —— 一个骗人的三角。
      ...(kids.length ? { children: kids } : {}),
    };
  };
  return (root.children ?? []).map((k) => conv(k, row.id));
}

/**
 * 给挂载行补上产品子树，其余行原样递归。
 *
 * <p><b>只用在管理面的表格上，不动 `rows`</b>：{@link parentTree}（表单里的父节点候选）
 * 读的是 `rows`，而虚拟行不是 `nav_nodes` 里的行，绝不能被选成父节点 —— 选中的话
 * 存回去的是一个不存在的 id。
 *
 * <p>挂载行**自己已有的 org 子行照旧保留**（排在前面）：它们是历史数据或 API 造的，
 * 侧栏里不显示（`renderMounted` 不读 org 子行），但要让人在这儿看得见、删得掉。
 * 行内那条「N 条子菜单不显示」的提示就是这么来的。
 */
function withProductKids(list: NavNodeRow[]): NavNodeRow[] {
  return list.map((n) => {
    const own = withProductKids(n.children ?? []);
    return { ...n, children: n.mounted ? [...own, ...productKids(n)] : own };
  });
}

/** 挂载行下面那几条**在侧栏里不会显示**的 org 子行（历史数据 / 走 API 造的）。 */
function staleKidCount(row: NavNodeRow) {
  return (row.children ?? []).filter((c) => !isVirtual(c)).length;
}

/** 表格渲染的行：两个壳根 + 各自的子树（`''` 筛选项 = 两个壳都要）。 */
const tableRows = computed<NavNodeRow[]>(() => {
  const out: NavNodeRow[] = [];
  for (const scope of ['workbench', 'project'] as NavScope[]) {
    if (scopeFilter.value && scopeFilter.value !== scope) continue;
    out.push(shellRow(scope, withProductKids(rows.value.filter((r) => r.scope === scope))));
  }
  return out;
});

/**
 * 一次遍历，算出表格渲染要用的两样东西：
 *
 * <ul>
 *   <li>{@code path} —— 每行的**分层位次路径**（`2.3` = 顶层第 2 支下的第 3 个）；</li>
 *   <li>{@code parent} —— 每行的父行（判断上移 / 下移能不能点，要取同层兄弟）。</li>
 * </ul>
 *
 * <p>壳根（`~workbench` / `~project`）**自己不占号**：一棵树里两个壳各有各的顶层，
 * 「工作台壳的第 1 个」与「项目壳的第 1 个」都该显示 `1`。所以编号从每个壳的 children 开始，
 * 而不是从 tableRows 的第一项开始。
 *
 * <p>每段是**位次**而非库里的 `sortOrder`：位次连续、无并列，也不会像原始值那样跨层连续
 * （产品清单导进来的是「整个 scope 前序编号」，父与首子同值）。
 *
 * <p>为什么要合成而不是把 `0002_0003` 这种路径**存进库**：菜单树是渲染时按 `parent_id`
 * 递归遍历的，存了路径也换不来「一条 SQL 排全树」的收益，却要付「挪一个节点要级联重写
 * 整支」的代价 —— 收益用不上、代价全占。
 */
const treeIndex = computed(() => {
  const path = new Map<string, string>();
  const parent = new Map<string, NavNodeRow | null>();
  const walk = (list: NavNodeRow[], owner: NavNodeRow, prefix: string) => {
    list.forEach((n, i) => {
      const me = prefix ? `${prefix}.${i + 1}` : String(i + 1);
      path.set(n.id, me);
      parent.set(n.id, owner);
      if (n.children?.length) walk(n.children, n, me);
    });
  };
  for (const shell of tableRows.value) {
    parent.set(shell.id, null);
    walk(shell.children ?? [], shell, '');
  }
  return { path, parent };
});

/** 这一行的分层位次（`2.3`）。壳根没有 —— 它不占号。 */
function pathIndex(record: NavNodeRow) {
  return treeIndex.value.path.get(record.id) ?? '';
}

/** 位次的真相，hover 才显示：第几层第几位 + 库里那个原始的排序值。 */
function sortTip(record: NavNodeRow) {
  const parts = pathIndex(record).split('.');
  return `第 ${parts.length} 层第 ${parts[parts.length - 1]} 位 · 排序值 ${record.sortOrder}`;
}

/**
 * 同层的**真实行**（不含虚拟行）。
 *
 * <p>虚拟行（产品清单带来的子项）不参与 org 的排序，也不能被移动 —— 它们的顺序在产品那边。
 * 它们在 `children` 里排在真实行**后面**（见 `withProductKids`），所以过滤掉之后剩下的顺序
 * 就是库里的顺序。
 */
function siblingsOf(record: NavNodeRow): NavNodeRow[] {
  const owner = treeIndex.value.parent.get(record.id);
  const list = owner
    ? owner.children ?? []
    : tableRows.value.find((s) => s.scope === record.scope)?.children ?? [];
  return list.filter((r) => !isVirtual(r));
}

/** 首个不能上移、末个不能下移。服务端越界时也不写库，这里只是别让人白点一下。 */
function canMove(record: NavNodeRow, delta: number) {
  const list = siblingsOf(record);
  const at = list.findIndex((r) => r.id === record.id);
  const to = at + delta;
  return at >= 0 && to >= 0 && to < list.length;
}

/** 正在移动的那一行（`null` = 没有在飞的请求）。同时只允许一个，顺带当了防重入。 */
const moving = ref<{ id: string; delta: number } | null>(null);

/**
 * 上移 / 下移一格。
 *
 * <p>与入口页 Tab 的 `moveLinkRow` 不同，这里**不能**只在前端换位：菜单页是逐行独立保存的
 * （每个动作都要落库），前端换位刷新一次就没了。所以走后端端点，成功后重新拉列表 ——
 * 服务端会把**整层**重编号（老数据里的并列、导入带来的外来值都在这一次写里被归一），
 * 只把返回值拼回本地状态会与库里的值漂开。
 */
async function move(record: NavNodeRow, delta: -1 | 1) {
  if (moving.value) return;
  moving.value = { id: record.id, delta };
  try {
    const r = await api.platform.moveNavNode(record.id, delta);
    // `changed: false` = 已经在首 / 末位。按钮本来就该是灰的，这里不再弹提示。
    if (r.changed) await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    moving.value = null;
  }
}

/**
 * 展开态。**壳根开着、底下的子菜单一律收起** —— 要看哪一支自己点开（2026-09-29 用户裁定）。
 *
 * <p>原先是「加载后整棵树展开一次」，理由是「默认收起会让人以为菜单没了好几层」。实践下来
 * 相反：菜单树是低频翻看的配置数据，全展开会让一屏塞满几十上百行，想找的那一支反而要滚
 * 半天。收起后一眼看到两个壳各自的顶层结构，要知道某一支底下有什么再点开。
 *
 * <p>**壳根（`~workbench` / `~project`）是例外，默认就展开**：它们是前端合成的分组行，
 * 不是真正的菜单。连它们也收起的话，打开这一页只剩两行、什么都看不到 —— 那就不是「收起
 * 子菜单」，是「什么都没显示」。
 *
 * <p>{@link load} 有意**不重设**它：保存 / 移动 / 删除 / 刷新之后，用户先前点开的那几支
 * 还是开着的，而不是每写一次就被收一次。
 */
const expandedKeys = ref<(string | number)[]>(['~workbench', '~project']);

const cols = [
  { title: '菜单', key: 'name' },
  { title: '路径 / 内容来源', key: 'path' },
  { title: '权限词', key: 'perm', width: 150 },
  { title: '图标', key: 'icon', width: 70 },
  // 120 而不是原先的 66：这一格现在放的是「分层位次 + 两个箭头按钮」。
  { title: '排序', key: 'sortOrder', width: 120 },
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
  // 目录页与外链**不是目录**：前者有自己的 Tab 页面（`path` 是服务端写的模板），
  // 后者有外部地址 —— 而外跳那一档的 `path` 是**空串**，只按 `!path` 判会把它
  // 显示成「目录（不可点）」，一个真能打开的入口看着像坏的。
  if (row.entryPage || row.externalUrl) return false;
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

function virtualTip(row: NavNodeRow) {
  return `这一行是照「${productLabel(row.product)}」自报的清单实时画出来的，不在菜单表里 ——
改它要去产品那边改（改完侧栏自动跟着变，这里不用动）。`;
}

function staleKidTip(row: NavNodeRow) {
  return `这 ${staleKidCount(row)} 条子菜单挂在挂载行下面，但**侧栏里不会显示**：
挂载行的内容是渲染时从产品清单拉来的（后端 renderMounted 只认产品清单，不读 org 侧的子行）。
删掉它们，或者把它们挂到别的目录下。`;
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
  source: 'org' as NavNodeSource,
  product: '',
  ref: '',
  title: '',
  isDir: false,
  path: '',
  perm: '',
  icon: '',
  enabled: true,
  adminOnly: false,
  emptyPolicy: 'hide' as NavGroupEmptyPolicy,
  // V29 / V30 / V31：目录页与外链菜单。
  //
  // `linkRows` 是**表单里的行**（一行一条引用，各带 `node:` / `product:` 前缀的挑选值
  // 与这一条 Tab 自己的名字），不是提交体里的字段 —— 提交时按行序铺成 `links`
  // （见 `rowsToLinks`）。名字刻意与 `links` 分开：两者同名会让「表单里的是哪一份」
  // 永远要回来看一眼。
  //
  // **行的顺序就是 Tab 顺序**（用户裁定），所以这里是个可上下移动的有序列表。
  linkRows: [] as LinkRow[],
  externalUrl: '',
  openMode: 'embed' as NavExternalOpenMode,
  authMode: 'none' as NavExternalAuthMode,
  /** 新填的 token 明文。**留空 = 不改**（已保存的不回显）。 */
  token: '',
  /** 服务端说的「配过没有」，只用来决定占位文案。 */
  hasToken: false,
  basicUser: '',
  password: '',
  hasPassword: false,
  visibility: 'all' as NavExternalVisibility,
});

/** 表单里的「来源」—— 五类节点（见 `NavNodeRow` 的注释）。 */
type NavNodeSource = 'org' | 'manual' | 'mounted' | 'entry' | 'external';

/**
 * 下拉值里两类引用的**值前缀**（V30）。
 *
 * <p>两类 id 来自两套命名空间（`nav_nodes.id` / 产品清单 id），理论上可能重名，
 * 而下拉的值只活在表单里、不参与提交，所以用一个前缀把它们彻底分开比赌不重名便宜。
 * 提交时按前缀还原成带 `kind` 的 {@link NavEntryLinkReq}（见 {@link rowsToLinks}）。
 */
const PICK_NODE = 'node:';
const PICK_PRODUCT = 'product:';

/** 产品清单节点在多选框里的值：产品码进得去，同一个 `ref` 在两个产品下不会互相顶掉。 */
function productPickValue(product: string, ref: string) {
  return `${PICK_PRODUCT}${product}|${ref}`;
}

/**
 * 「挂进来的菜单」下拉的候选：**两类引用合成一份带类型的选项**。
 *
 * <p><b>第一类：本站菜单</b>（V29）—— **同一个壳**里的、**不是目录页**的所有节点。
 * 两条过滤与 `NavNodeService.applyLinks` 的校验一一对应，前端先挡住，省一次「填完才被拒」：
 * 跨壳挂会让这一支在侧栏里整片消失（与父子关系同一个理由）；目录页不能再挂目录页
 * （Tab 里嵌 Tab 说不清该显示什么，这一条同时把多级循环全挡掉）。自己也要排掉。
 *
 * <p><b>第二类：产品清单里的节点</b>（V30）—— 来源是各产品自报的 `menu.json`
 * （`navCandidates`），按产品分块、只列**同一个壳**的。这一类在 `nav_nodes` 里没有行，
 * 所以只能从清单里选；**目录也列出来**：选一个目录 = 把这一支整支带进这个入口页，
 * 由入口页把它们摊成一排 Tab（每条子项一个 Tab）。
 *
 * <p><b>这里只能看到静态清单</b>：产品**运行期**才报上来的子项（如「建模中心」下按项目
 * 登记的那些分层）在候选里没有，选不到 —— 那是服务端在配置时也看不到的东西。想要它们，
 * 用「整支一起挂」：选产品清单里的那一支目录。
 *
 * <p>用**扁平列表**而不是树：`a-tree-select` 的勾选策略（勾父节点要不要连带勾子节点）
 * 在「一行挑一条、每行还要各自填 Tab 名并上下移动」这件事上很难讲清。
 */
const linkOptions = computed<{ value: string; label: string }[]>(() => {
  const out: { value: string; label: string }[] = [];
  const walk = (list: NavNodeRow[], depth: number) => {
    for (const n of list) {
      if (n.scope === form.scope && n.id !== form.id && !n.entryPage) {
        out.push({
          value: PICK_NODE + n.id,
          // 用全角空格缩进表达层级：下拉里没有树的连线，靠前缀区分「这是谁的子菜单」
          label: `${'　'.repeat(depth)}${n.label}${isDir(n) ? '（目录）' : ''}`,
        });
      }
      if (n.children?.length) walk(n.children, depth + 1);
    }
  };
  walk(rows.value, 0);

  for (const product of configuredProducts.value) {
    const report = candidates.value.find((c) => c.product === product);
    // 清单拉不到（或还没登记页面地址）＝ 这一支没有候选可列。不列脏数据，
    // 也不在这里报错 —— 页面顶部已经有一份「哪些产品的清单拉不到」的提示。
    if (!report?.ok) continue;
    const suffix = ` · ${productLabel(product)}`;
    const walkMenus = (list: MenuCandidate[], depth: number) => {
      for (const m of list) {
        if (m.scope === form.scope) {
          out.push({
            value: productPickValue(product, m.id),
            // 「（目录）」的判据与本站菜单不同：清单项有没有页面看 `path`，不看它在树里的位置
            label: `${'　'.repeat(depth)}${m.label}${m.path ? '' : '（目录）'}${suffix}`,
          });
          walkMenus(m.children ?? [], depth + 1);
        }
      }
    };
    walkMenus(report.menus, 0);
  }
  return out;
});

/** 下拉的过滤：按菜单名匹配，别把缩进用的全角空格算进去。 */
function filterLinkOption(input: string, option: { label: string }) {
  return option.label.replace(/　/g, '').includes(input);
}

/**
 * 表单里的一行：挑了哪一条 + 这一条 Tab 叫什么（空 = 用被挂菜单自己的名字）。
 *
 * <p>`pick` 是下拉里的值（带 `node:` / `product:` 前缀），**不参与提交** ——
 * 提交时按前缀还原成 {@link NavEntryLinkReq}（见 {@link rowsToLinks}）。
 */
type LinkRow = { pick: string; label: string };

/**
 * 表单里的行 → 提交体里的 `links`（V31）。
 *
 * <p><b>数组顺序就是 Tab 顺序</b>（用户裁定：行顺序 = Tab 顺序），所以这里不再分类，
 * 只按行序原样铺开。V30 那个「按前缀拆成两个数组」的写法正因为拼不回一个混合顺序
 * 才被换掉 —— Tab 名同时也是从这条路径进服务端的。
 *
 * <p>认不出的值 / 空行直接丢掉：前者只可能是「选项已经不在列表里了」（产品发版删了
 * 那个节点），而提交一个已经不存在的引用只会换来一句 400。
 */
function rowsToLinks(rows: LinkRow[]): NavEntryLinkReq[] {
  const out: NavEntryLinkReq[] = [];
  for (const row of rows) {
    const label = row.label.trim();
    if (row.pick.startsWith(PICK_NODE)) {
      const target = row.pick.slice(PICK_NODE.length);
      if (target) out.push({ kind: 'node', target, label });
      continue;
    }
    if (row.pick.startsWith(PICK_PRODUCT)) {
      const rest = row.pick.slice(PICK_PRODUCT.length);
      const bar = rest.indexOf('|');
      if (bar <= 0) continue;
      const product = rest.slice(0, bar);
      const ref = rest.slice(bar + 1);
      if (product && ref) out.push({ kind: 'product', product, ref, label });
    }
  }
  return out;
}

/** 入口页挂了几条。表格里那一格用它。 */
function entryPickCount(row: NavNodeRow): number {
  return (row.links ?? []).length;
}

/** 已保存的入口页 → 表单里的行（回显）。服务端已按 Tab 顺序给好，原样铺开即可。 */
function linkRowsOf(row: NavNodeRow): LinkRow[] {
  return (row.links ?? []).map((l) => ({
    pick:
      l.kind === 'node'
        ? PICK_NODE + (l.target ?? '')
        : productPickValue(l.product ?? '', l.ref ?? ''),
    label: l.label ?? '',
  }));
}

/**
 * 行的上下移动 —— 这就是**唯一**的 Tab 排序手段（顺序 = 行序）。
 *
 * <p>不做拖拽：这个表单在一个抽屉里，而抽屉里已经有一层纵向滚动，
 * 拖拽与滚动手势会打架（管理页一屏几十条菜单时尤其明显）。两个按钮没有这个问题。
 */
function moveLinkRow(index: number, delta: number) {
  const next = index + delta;
  if (next < 0 || next >= form.linkRows.length) return;
  const [row] = form.linkRows.splice(index, 1);
  form.linkRows.splice(next, 0, row);
}

/**
 * 复制已保存的凭据（平台管理员专属接口）。
 *
 * <p>服务端**只在这一个接口给明文**（见 `NavNodeService.credential`）：账号密码那一档
 * 浏览器不允许代填，不给人复制就完全是个死字段。
 */
async function copyCredential() {
  if (!form.id) return;
  try {
    const cred = await api.platform.navNodeCredential(form.id);
    const text =
      cred.authMode === 'basic'
        ? `${cred.basicUser}\t${cred.password}`
        : cred.token;
    if (!text.trim()) {
      message.info('这一条还没有保存过凭据');
      return;
    }
    await navigator.clipboard.writeText(text);
    message.success('已复制');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

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
/**
 * 父节点候选：按壳取行，排除挂载行与被编辑的那一行。
 *
 * <p>排除挂载行是因为**挂上去也不会显示** —— 后端 `renderMounted` 只渲染产品清单的子树，
 * 根本不读 org 侧的子行。后端另有一道 400 拦着（API 也能造数据），这里提前不让选。
 *
 * <p>排除一个节点时它的子树也不出现：挂载行自己的 org 子行同样进不了候选，
 * 没有「挪到挂载行下面」这条路可走。
 */
function parentChoices(scope: NavScope, excludeId = ''): Record<string, unknown>[] {
  const walk = (list: NavNodeRow[]): Record<string, unknown>[] =>
    list
      .filter((n) => n.id !== excludeId && !n.mounted)
      .map((n) => {
        const kids = walk(n.children ?? []);
        return {
          value: n.id,
          title: n.label + (isDir(n) ? '（目录）' : ''),
          ...(kids.length ? { children: kids } : {}),
        };
      });
  return walk(rows.value.filter((r) => r.scope === scope));
}

const parentTree = computed(() => parentChoices(form.scope, form.id));

/** 挂载弹窗里的父节点候选：跟的是**那个弹窗自己的**归属壳，不是表单的。 */
const pickParentTree = computed(() => parentChoices(pickScope.value));

function openCreate(parent: NavNodeRow | null) {
  const scope = parent && !isShell(parent) ? parent.scope : scopeFilter.value || 'workbench';
  Object.assign(form, {
    id: '',
    scope,
    parentId: parent && !isShell(parent) ? parent.id : '',
    source: 'org' as NavNodeSource,
    product: '',
    ref: '',
    title: '',
    isDir: false,
    path: '',
    perm: '',
    icon: '',
    enabled: true,
    adminOnly: false,
    emptyPolicy: 'hide',
    linkRows: [],
    externalUrl: '',
    openMode: 'embed' as NavExternalOpenMode,
    authMode: 'none' as NavExternalAuthMode,
    token: '',
    hasToken: false,
    basicUser: '',
    password: '',
    hasPassword: false,
    visibility: 'all' as NavExternalVisibility,
  });
  pathLocked.value = false;
  open.value = true;
}

function openEdit(row: NavNodeRow) {
  Object.assign(form, {
    id: row.id,
    scope: row.scope,
    parentId: row.parentId ?? '',
    source: sourceOf(row),
    product: row.product,
    ref: row.ref,
    title: row.label,
    isDir: isDir(row),
    // 目录页 / 外链的 `path` 是服务端写的**模板**，不该出现在可编辑的输入框里 ——
    // 来源从这两档切回「org 自己的页面」时，那一格应当是空的（逼管理员填一个真路径），
    // 而不是留着 `{node}` 让人以为可以用。
    path: row.entryPage || row.externalUrl ? '' : row.path,
    perm: row.perm,
    icon: row.icon,
    enabled: row.enabled,
    adminOnly: row.adminOnly,
    emptyPolicy: row.emptyPolicy ?? 'hide',
    linkRows: linkRowsOf(row),
    externalUrl: row.externalUrl ?? '',
    openMode: row.openMode ?? 'embed',
    authMode: row.authMode ?? 'none',
    // 明文永远不回显（服务端只给 hasToken / hasPassword）—— 留空即「不改」
    token: '',
    hasToken: Boolean(row.hasToken),
    basicUser: row.basicUser ?? '',
    password: '',
    hasPassword: Boolean(row.hasPassword),
    visibility: row.visibility ?? 'all',
  });
  pathLocked.value = false;
  void ensurePermOptions(row.product);
  open.value = true;
}

/** 一行 → 表单的「来源」。判据与 `NavNodeRow` 的注释同一套，别在别处另写一份。 */
function sourceOf(row: NavNodeRow): NavNodeSource {
  if (row.entryPage) return 'entry';
  if (row.externalUrl) return 'external';
  if (row.mounted) return 'mounted';
  return row.product ? 'manual' : 'org';
}

/**
 * 这一行给不给「删除」。
 *
 * <p><b>只有从产品来的（挂载 / 手工复制）能删</b>：它们的定义在产品那边，删掉只是
 * 「不再跟随产品」，随时能再挂回来。org 自己的四类（页面 / 目录 / 入口页 / 外链）
 * 删了找不回来 —— 路径是内部知识（`/org/project/{code}/members`），外链凭据还只存不回显 ——
 * 所以那一类只给「停用」（见 `toggleEnabled`）。
 *
 * <p><b>判据走 `sourceOf`，不另写</b>：后端 `NavNodeService.delete()` 的守卫用的是
 * `product` 是否为空，这里 `sourceOf` 归出来的 `mounted` / `manual` 正是「product 非空」
 * 那一支。自己另写一个（比如 `!row.mounted`，或 `!row.product`）都会漂移：前者把入口页
 * 和外链也当成 org 自有（那两个的 `mounted` 都是假），后者会把它们放行（它们的 `product`
 * 也是空 —— 后端强制 entry/external 与 product 互斥），于是界面上有删除、点了却 400。
 */
function canDelete(row: NavNodeRow): boolean {
  const s = sourceOf(row);
  return s === 'mounted' || s === 'manual';
}

/**
 * 停用 / 启用 org 自己的菜单。
 *
 * <p>不做二次确认：这个动作**可逆**（同类的 `tenants.vue` / `users.vue` 的停用也不确认），
 * 而它的反面 —— 删除 —— 才有那一层确认。
 *
 * <p>停用后的隐藏是后端一处收口的（`NavNodeService.enabledRows()`，侧栏 / 入口页 /
 * 外链三个消费面全走它），前端不另做过滤：这里只负责把开关翻过去。
 */
async function toggleEnabled(row: NavNodeRow) {
  try {
    await api.platform.updateNavNode(row.id, { enabled: !row.enabled });
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
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
  if ((form.source === 'manual' || form.source === 'mounted') && !form.product) {
    message.warning('请选择产品');
    return;
  }
  if (form.source === 'mounted' && !form.ref) {
    message.warning('请选择要挂载的产品节点');
    return;
  }
  if (form.source === 'external') {
    const url = form.externalUrl.trim();
    if (!url) {
      message.warning('请填写外链地址');
      return;
    }
    // 与服务端的校验同一口径（先挡一次，省一次「填完才被拒」）。
    if (!/^https?:\/\//i.test(url)) {
      message.warning('外链地址要以 http:// 或 https:// 开头');
      return;
    }
  }
  if (
    (form.source === 'org' || form.source === 'manual') &&
    !form.isDir &&
    !form.path.trim()
  ) {
    message.warning('请填写路径，或把这一条设成目录');
    return;
  }
  busy.value = true;
  const isProduct = form.source === 'manual' || form.source === 'mounted';
  const isEntry = form.source === 'entry';
  const isExternal = form.source === 'external';
  // 挂进来的 Tab 是**一次全量替换**（不传 = 不改，空数组 = 都清空）。
  // 行序原样进数组下标 —— 服务端就是按它定 Tab 顺序的（见 `rowsToLinks`）。
  const links = isEntry ? rowsToLinks(form.linkRows) : undefined;
  const body = {
    scope: form.scope,
    parentId: form.parentId || '',
    title: form.title.trim(),
    // 目录 = 空路径（服务端就是这么判的）；**挂载**行的路径由产品清单决定，不传。
    // 目录页 / 外链的路径由**服务端写模板**，这里一律传空串（传了也会被覆盖）。
    //
    // 判据不能写成 `isProduct`：它含 `manual`（手工复制产品页面），而那一档的路径正是
    // 从产品清单预填、要**提交**的东西 —— 写成 `isProduct` 会让手工建的菜单 path 落成
    // 空串，表现为「保存成功、侧栏里点进去是目录」。
    path: form.source === 'mounted' || isEntry || isExternal || form.isDir ? '' : form.path.trim() || '/',
    perm: form.perm.trim(),
    icon: form.icon || '',
    // 排序**不发**（原先发的是 `form.sortOrder ?? 0`）：不传 = 服务端落在同层末尾。
    // 这一格是「排序不再由管理员手填」的关键 —— 前端一直发 0 的话，服务端那个缺省分支
    // 永远触发不到，等于没改。顺序改由列表里的 ↑↓ 调（见 `move`）。
    enabled: form.enabled,
    adminOnly: form.source === 'org' || isEntry ? form.adminOnly : false,
    product: isProduct ? form.product : '',
    ref: form.source === 'mounted' ? form.ref : '',
    mounted: form.source === 'mounted',
    emptyPolicy: form.emptyPolicy,
    // 这三项**每次都要显式传**（服务端只在字段非空时才改）：从目录页/外链改回别的来源时，
    // 漏传会让它继续当目录页 —— 表现为「改成普通页面了，侧栏里点进去还是一张表」。
    entryPage: isEntry,
    externalUrl: isExternal ? form.externalUrl.trim() : '',
    openMode: isExternal ? form.openMode : undefined,
    authMode: isExternal ? form.authMode : undefined,
    // 凭据：只在填了新值时才传（空 = 保持原值，服务端就是这么读的）
    token: isExternal ? form.token : undefined,
    basicUser: isExternal ? form.basicUser.trim() : undefined,
    password: isExternal ? form.password : undefined,
    visibility: isExternal ? form.visibility : undefined,
    // 不传 = 不改；传数组 = 全量替换（空数组就是清空）。非目录页不传，
    // 服务端会把残留的关联清掉（`applyLinks` 的 `!isEntry` 分支）。
    links,
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
 * 挂载草稿（`create` 那一档）：树里选中的节点 + 管理员在右边改过的名字/权限词/父节点。
 *
 * <p>有它才有「挂到哪里了」的答案 —— 原先点一下树就立刻建行，位置固定是顶层，
 * 名字也照抄产品，管理员在按下之前看不到任何一项。
 *
 * <p>没有排序：挂上来的行落同层末尾，之后在列表里用 ↑↓ 调（见 `move`）。
 */
const pickDraft = ref<{
  node: MenuCandidate;
  parentId: string;
  title: string;
  perm: string;
} | null>(null);
const pickBusy = ref(false);

/**
 * 收起选择器。
 *
 * <p>从表单里点进来的（`mounted` / `manual`）取消后要**回到表单** —— 否则管理员填了一半的
 * 那一屏就凭空没了。工具栏上那个入口（`create`）背后没有表单可回。
 */
/** 关闭挂载抽屉。底栏「取消」和右上角 X 都走这里，**不能**只写 `pickOpen = false`：
 *  后两档是表单里点「挑一条」进来的，直接关抽屉会把用户正在填的那张表单一起丢掉。 */
function onPickCancel() {
  pickOpen.value = false;
  if (pickTarget.value !== 'create') open.value = true;
}

function openPicker(target: 'create' | 'mounted' | 'manual') {
  pickTarget.value = target;
  // 工具栏那个入口（create）背后没有表单，归属壳应当跟着**列表上方的筛选**走：
  // `form.scope` 是上次打开表单击「新增/编辑」留下的值，与眼前这一屏无关，而它
  // 现在还兼着「按壳过滤清单树」的职责 —— 初值取错会让你看不到想挂的那一支。
  // 从表单里点进来的两档（mounted / manual）照旧用表单自己的壳。
  pickScope.value = target === 'create' ? scopeFilter.value || form.scope : form.scope;
  pickProduct.value = form.product || configuredProducts.value[0] || '';
  pickTree.value = [];
  pickError.value = '';
  pickDraft.value = null;
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
      // 挂载**按壳过滤**：产品清单里同一个页面往往两套壳各报一份（`/lineage/workbench`
      // 是工作台壳的、`/lineage` 是项目壳的）。不滤的话两份都会列出来，挂错壳那一条
      // 存进去也不算错（scope 是管理员选的），但点开是另一个壳的页面地址。
      //
      // 只在「挂载」那一档滤：另外两档是往表单里回填，填什么由选中的节点自己决定。
      const list =
        pickTarget.value === 'create'
          ? found.menus.filter((m) => m.scope === pickScope.value)
          : found.menus;
      pickTree.value = toPickTree(list);
    }
  } catch (e) {
    pickError.value = e instanceof Error ? e.message : String(e);
  } finally {
    pickLoading.value = false;
  }
}

/**
 * 清单树 → antd 树。`product` 传了就**每一层**都编成 `产品/节点id`，不传则用裸 id。
 *
 * <p>那个前缀不是装饰：这一屏下游两处都是按 {@link candKey} 取值的 —— 右侧清单查
 * {@link candByKey}、导入时 `walkImport` 用 `picked.has(candKey(...))` 判「勾没勾」。
 * 只给最外一层加前缀（这个函数原先的调用方式）会让**深度 ≥3 的节点全部对不上号**：
 * 勾了却不显示在清单里，保存时又被当成「没勾」跳过 —— 表现为目录建出来是空的，
 * 而空目录默认 `emptyPolicy=hide`，侧栏里**整支消失且没有任何报错**。
 *
 * <p>默认（不传 `product`）保持裸 id：另一个调用点 {@link onPickSelect} 要拿它去
 * {@link candNode} 里查。
 */
function toPickTree(list: MenuCandidate[], product?: string): Record<string, unknown>[] {
  return list.map((m) => {
    const kids = toPickTree(m.children ?? [], product);
    return {
      key: product ? candKey(product, m.id) : m.id,
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

  // 挂载：选中只是**填草稿**，不落库。原先点一下树就立刻创建，管理员既看不到会建出
  // 什么名字、也选不了挂到哪一级 —— 「选完不知道挂到哪里了」就是这么来的。
  pickDraft.value = {
    node,
    parentId: '',
    title: node.label,
    perm: node.perm,
  };
}

/** 归属壳变了要重取树（清单按壳过滤，见 `loadPickMenus`），草稿也跟着作废。 */
function onPickScopeChange() {
  pickDraft.value = null;
  void loadPickMenus();
}

/** 在管理页那棵树里按 id 找一行（只用于把父节点 id 说成人话）。 */
function findRow(list: NavNodeRow[], id: string): NavNodeRow | undefined {
  for (const n of list) {
    if (n.id === id) return n;
    const hit = findRow(n.children ?? [], id);
    if (hit) return hit;
  }
  return undefined;
}

/** 「挂到哪里去了」——这句提示就是这次改动的目的，别只说「已挂载」。 */
function mountWhere(parentId: string): string {
  const shell = scopeLabel(pickScope.value);
  if (!parentId) return `「${shell}」顶层`;
  return `「${shell} › ${findRow(rows.value, parentId)?.label ?? parentId}」`;
}

/** 把草稿落成一条挂载行。 */
async function confirmMount() {
  const d = pickDraft.value;
  if (!d) return;
  pickBusy.value = true;
  try {
    await api.platform.createNavNode({
      scope: pickScope.value,
      parentId: d.parentId,
      title: d.title.trim() || d.node.label,
      // 路径留给渲染时从产品清单取（`renderMounted` 会覆盖它）：手工填的那条对不上
      // 产品真实路由，点开就是 404。
      path: '',
      perm: d.perm,
      icon: d.node.icon,
      // 排序不发：挂上来的行落在同层末尾，之后在列表里用 ↑↓ 调
      enabled: true,
      product: pickProduct.value,
      ref: d.node.id,
      mounted: true,
      emptyPolicy: 'hide',
    });
    message.success(`已挂载到${mountWhere(d.parentId)}`);
    pickOpen.value = false;
    pickDraft.value = null;
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    pickBusy.value = false;
  }
}

// ---- 从服务拉取（批量导入为手工行） ----
const fetchOpen = ref(false);
const fetching = ref(false);
const submitting = ref(false);
const checkedKeys = ref<(string | number)[]>([]);
/** 勾选的 key 集合（`产品 + 节点 id`）。半选的父节点不进来 —— 它们由「透明层」逻辑兜住。 */
const picked = ref<Set<string>>(new Set());
/** 导入草稿：每个勾中的候选可以改「建到哪个壳、叫什么、判什么权」。排序不在其中 —— 逐条建时落同层末尾，顺序照产品清单的层级走。 */
const draft = reactive<Record<string, { scope: NavScope; title: string; perm: string }>>({});

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
      children: toPickTree(p.menus, p.product),
    }))
);

const failedCandidates = computed(() => candidates.value.filter((c) => !c.ok));
const fetchableCount = computed(() => candidates.value.filter((c) => c.ok && c.menus.length).length);

const pickedCols = [
  { title: '归属壳', key: 'scope', width: 116 },
  // 200 而不是 170：这一格放的是**输入框**，宽度就是能看见几个字的极限，
  // 名字普遍四到六个汉字，170 之下「规范中心」都显示不全。总和与上面的 scroll.x 对齐。
  { title: '菜单名', key: 'title', width: 200 },
  { title: '路径', key: 'path', width: 216 },
  { title: '权限词', key: 'perm', width: 160 },
  // 没有「排序」列：导入按产品清单的层级**逐条建**（父建完再建子），服务端的
  // 「同层最大 + 10」自然给出 10/20/30。让管理员在这一屏手填数字，等于把 org 的排序
  // 从产品层级里拆出来 —— 而这一屏的用途恰恰是「照产品那样抄一份」。
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
        // 排序不发（原先抄的是产品清单的 `node.sort`）：逐条建、**父建完再建子**，
        // 服务端的「同层最大 + 10」自然给出 10/20/30，与产品清单的层级一致。
        // 抄产品那个值反而会把「整个 scope 前序编号」落进 org 的数字空间（父与首子同值）。
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
  // **这里不碰 {@link expandedKeys}**（2026-09-29 用户裁定「默认收起」）。保存 / 移动 /
  // 删除 / 刷新都会走这个函数，一旦在这里重设展开态，就是「写完一次被收一次」，比默认
  // 收起本身更烦人；用户点开的那几支应当在写操作之后保持原样。
  //
  // 有挂载行时才去拉产品清单：一份挂载都没有时「失配」不可能发生，
  // 没必要为此在每次打开这一页时都去打一圈产品（某个产品挂了就是一次次等满超时）。
  if (rows.value.some((r) => r.mounted) || hasProducts.value) await refreshCandidates();
}

onMounted(load);
</script>

<style scoped>
/* 「挂进来的菜单」：一行一条引用 —— 挑哪条菜单、这条 Tab 叫什么、排在哪儿、删掉 */
.link-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.link-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 菜单名可能很长（全角空格缩进 + 产品后缀），让它吃掉剩余宽度；Tab 名固定一栏 */
.link-row-pick {
  flex: 1;
  min-width: 0;
}
/* 180 是「Tab 名够看」与「下拉够看」的折中：这一行总宽固定（弹窗 820），
   多给 Tab 名 40px 就是从下拉身上拿 40px —— 而下拉才是这一行真正要读的东西 */
.link-row-label {
  width: 180px;
  flex: none;
}
.sort-note {
  margin: 0 0 10px;
  font-size: 12px;
}
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
  /* 抽屉内的可用高度：视口减去抽屉头、底栏和 body 上下留白。
     原先留 260 是因为按钮条排在内容末尾，现在它搬进抽屉底栏常驻了。 */
  max-height: calc(100vh - 210px);
  overflow: auto;
}
.pick-right {
  min-width: 0;
}
/* 挂载弹窗的左栏比导入抽屉宽一点：它还要放「归属壳 / 产品」两个表单项 */
.mount-split {
  grid-template-columns: 380px minmax(0, 1fr);
}
/* 只读的「内容来源 / 路径」：它们是产品报的，不给改，所以在表单外面单独一块 */
.mount-src {
  display: grid;
  gap: 6px;
  margin: 0 0 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.02);
}
.mount-src > div {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 8px;
  font-size: 12px;
  line-height: 1.5;
}
.mount-src dt {
  color: var(--muted);
}
.mount-src dd {
  margin: 0;
  word-break: break-all;
}
.mount-src code,
.pick-head code {
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.06);
}
.mount-note {
  margin: 0 0 12px;
  font-size: 12px;
}
/* 定宽列里的长路径：省略号收尾。不加的话它会顶破列宽，把「菜单名」那格挤成一个字。 */
.cell-path {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
/* 排序列：位次在左、两个箭头在右。不换行 —— 一换行行高就忽大忽小，
   而这一列每一行都长这样，参差会非常明显。 */
.cell-sort {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}
/* 等宽数字：`1.2` 与 `2.11` 的位数不同，不等宽的话上下两行的位次对不齐。 */
.sort-idx {
  font-variant-numeric: tabular-nums;
}
.sort-move {
  display: flex;
  flex-shrink: 0;
}
/* 箭头按钮压小一点：antd 的默认尺寸在表格行里会把行撑高，而这两个键是高频小动作。 */
.sort-move :deep(.ant-btn) {
  min-width: 22px;
  height: 22px;
  padding: 0 4px;
  font-size: 12px;
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
/* 外链凭据那一行：输入框 + 「复制已保存的」。账号密码有两个输入框，所以让它纵向排。 */
.cred {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
}
.cred > .ant-input-affix-wrapper,
.cred > .ant-input {
  width: 100%;
}
</style>
