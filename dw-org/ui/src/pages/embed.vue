<template>
  <div class="embed-page">
    <p v-if="!settled" class="muted pad">正在加载…</p>

    <div v-else-if="!embeddable" class="card pad">
      <h3>该产品暂不支持在平台内嵌</h3>
      <p class="muted">
        它的前端还没有接入嵌入协议（收不到平台推的登录态），塞进壳里只会是一个打不开的空页面。
      </p>
      <p v-if="frontendUrl"><a :href="frontendUrl" target="_blank" rel="noreferrer">在新窗口打开它</a></p>
    </div>

    <div v-else-if="!frontendUrl" class="card pad">
      <h3>这一页暂时打不开</h3>
      <p v-if="licensed" class="muted">
        该产品还没有登记页面地址 —— 到
        <router-link :to="ORG_PAGES.platformServices">服务注册</router-link>
        填上它的前端地址，再把页面配进
        <router-link :to="ORG_PAGES.platformNav">菜单管理</router-link>。
      </p>
      <p v-else class="muted">
        当前组织没有开通这个产品。到
        <router-link :to="ORG_PAGES.platformTenants">租户</router-link>
        里给它开一下，菜单才会出现。
      </p>
    </div>

    <ProductEmbed v-else :product="product" :label="label" :src="src" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import ProductEmbed from '../components/ProductEmbed.vue';
import { ORG_PAGES } from '../config/pages';
import { productEmbedUrl } from '../config/product';
import { isEmbeddable } from '../config/products';
import type { ProductModule } from '../config/iam';
import { app, currentProject, currentTenant, hasModule, loadNav, navTree } from '../stores/app';
import type { NavNodeRow } from '../api/client';

defineOptions({ name: 'PortalEmbed' });

const route = useRoute();

/** 路由参数里的产品码（`/org/embed/:product/...`）。 */
const product = computed(() => String(route.params.product ?? ''));

/** 子应用内路径：`/org/embed/metadata/lineage/tables` → `/lineage/tables`。 */
const subPath = computed(() => {
  const rest = route.params.pathMatch;
  const parts = (Array.isArray(rest) ? rest : [rest])
    .map((s) => String(s ?? ''))
    .filter(Boolean);
  return parts.length ? `/${parts.join('/')}` : '/';
});

const embeddable = computed(() => isEmbeddable(product.value));

/** 该产品是否在租户许可里 —— 只用来把「没开通」和「没登记地址」两种成因分开说。 */
const licensed = computed(() => hasModule(product.value as ProductModule));

/** 当前在哪个壳里（见 `router/index.ts` 的 `meta.shell`）。 */
const shell = computed(() =>
  route.matched.some((r) => r.meta.shell === 'project') ? 'project' : 'workbench'
);

/**
 * 把菜单树按深度优先摊平 —— 产品节点可能挂在任意层级下（挂在某个目录里、
 * 或者本身就是挂载展开出来的子树），只扫顶层会漏掉它们。
 */
function flattenProducts(nodes: NavNodeRow[]): NavNodeRow[] {
  const out: NavNodeRow[] = [];
  for (const n of nodes) {
    out.push(n);
    if (n.children?.length) out.push(...flattenProducts(n.children));
  }
  return out;
}

/**
 * 当前产品在菜单里的那一项。地址与标题都从它来，不另外查一次服务表。
 *
 * <p>优先取**当前壳**的那一项：同一条子路径可以两个壳各挂一份，标题也可能不同
 * （如工作台叫「元数据服务」、项目里叫「数据地图」），取错会把别人的标题画在页头。
 * 地址是产品级的，所以两处都能用；只有标题有这个讲究。
 */
const menu = computed(() => {
  const ofProduct = flattenProducts(navTree.value).filter((n) => n.product === product.value);
  return ofProduct.find((n) => n.scope === shell.value) ?? ofProduct[0];
});
const frontendUrl = computed(() => menu.value?.frontendUrl ?? '');
const label = computed(() => menu.value?.label ?? '产品页面');

const src = computed(() =>
  productEmbedUrl(product.value, subPath.value, frontendUrl.value, {
    tenantName: currentTenant.value?.name ?? '',
    projectName: currentProject.value?.name ?? '',
    userId: app.currentUserId ?? '',
  })
);

/**
 * 菜单先到位再渲染 iframe。
 *
 * <p>直接刷新 `/org/embed/metadata/lineage/tables` 时菜单还没拉回来，此时 `frontendUrl`
 * 为空 —— 若立刻渲染兜底文案，用户会先看到「这一页暂时打不开」再看到页面，像是出了错。
 * 等这一次拉取落定再决定，代价只是多一次幂等的 `GET /api/nav`。
 */
const settled = ref(false);
onMounted(async () => {
  await loadNav();
  settled.value = true;
});
</script>

<style scoped>
.embed-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pad {
  margin: 16px;
}
</style>
