<template>
  <a-select
    v-if="options.length > 1"
    :value="currentProject?.id"
    :options="options"
    :placeholder="currentProject ? currentProject.name : '切换项目'"
    style="min-width: 180px"
    :loading="switching"
    @change="onPick"
  />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { isOrgUi } from '../config/product';
import {
  currentProject,
  enterProject,
  reloadNav,
  servicesReady,
  tenantProjects,
} from '../stores/app';

const route = useRoute();
const router = useRouter();

/** 切换期间（重拉菜单 + 换地址）把下拉置忙，避免连点两次落到两个项目上。 */
const switching = ref(false);

const options = computed(() =>
  tenantProjects.value
    .filter((p) => (p.status ?? 'active') !== 'disabled')
    .map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))
);

/**
 * 切换项目 —— **只换项目，不换页面**。
 *
 * <p>以前这里无条件 `router.push('/org/project/{新码}')`：切完项目就被丢到项目首页
 * （= 菜单里第一条能嵌的产品页面），用户正在看的那一页没了。现在改成把地址里的
 * 项目码换掉、其余原样保留（见 {@link keepPath}）。
 *
 * <p>菜单必须**在这里重拉**：它的结论依赖当前项目（服务端按这个人在这项目下的角色
 * 过滤权限词，见 `leaveProject` 的注释）。以往这一步是白送的 —— 切换后总要跳转到
 * 项目壳的某个页面，那些页面 `onMounted` 里各有一句 `loadNav()`。改成不跳之后，
 * 组件被 vue-router 复用、不会重新挂载，那次拉取就不会发生了，侧栏会一直停在上一个
 * 项目的权限视角上。
 */
async function onPick(id: unknown) {
  if (typeof id !== 'string' || id === currentProject.value?.id) return;
  switching.value = true;
  try {
    await enterProject(id);
    // `enterProject` 遇到停用的项目只弹一句提示、不改当前项目 —— 那就什么都没发生，
    // 别再去动地址。
    if (currentProject.value?.id !== id) return;
    if (!isOrgUi()) {
      router.push('/model');
      return;
    }
    await reloadNav();
    // 服务目录是**租户级**的（同一租户下各项目共用一份），进哪个项目都一样 ——
    // 等它只是为了首次直接落在项目壳里时它还没拉回来，切项目本身不需要重拉。
    await servicesReady();
    keepPath();
  } finally {
    switching.value = false;
  }
}

/**
 * 把地址里的项目码换成当前项目那个，**其余部分原样保留** —— 用户停在哪一页就留在哪一页。
 *
 * <p>只换「项目码那一段」而不是重新拼路径：`route.fullPath` 里还带着子路径、query 与
 * hash，逐个搬容易漏。做法是用**当前**码拼出前缀，切掉它，剩下的整段接到新前缀后面。
 *
 * <p>两个易错点：
 * <ul>
 *   <li>前缀必须用 `encodeURIComponent` 还原成**地址里的写法**。`route.params.code`
 *       是解码后的（vue-router 会解），而 `fullPath` 保留编码，直接拿原值比会匹配不上，
 *       表现是切完项目静默跳回首页 —— 正好是这次要修的症状。</li>
 *   <li>切掉前缀前要确认边界是 `/`（或用尽整串）。项目码可能互为前缀（`pj0` 与 `pj03`），
 *       `startsWith` 会匹配上，切完剩下 `3/embed/...` 这种残缺路径。</li>
 * </ul>
 *
 * <p>目标项目下若没有这一页（产品没挂菜单、组织没开通），**不做特殊回落**：`embed.vue`
 * 自己会画「该产品暂不支持在平台内嵌 / 这一页暂时打不开」并给出修复入口，比默默把人
 * 弹回首页诚实 —— 他要的那一页确实不在。</p>
 */
function keepPath() {
  const code = currentProject.value?.code;
  if (!code) return;
  const here = String(route.params.code ?? '');
  // 地址里没带项目码 = 当前不在项目壳（本组件只画在项目壳顶栏，正常到不了这里）——
  // 那就退化成进项目首页，总比停在原处不动强。
  const from = here ? `/org/project/${encodeURIComponent(here)}` : '';
  const rest =
    from && route.fullPath.startsWith(`${from}/`) ? route.fullPath.slice(from.length) : '';
  const next = `/org/project/${encodeURIComponent(code)}${rest}`;
  if (next !== route.fullPath) router.push(next);
}
</script>
