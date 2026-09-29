import { productEmbedUrl } from './product';
import { appearanceOf } from '../stores/prefs';
import { app, currentProject, currentTenant } from '../stores/app';
import type { ShellScope } from './sysNav';

/**
 * 壳里嵌一个产品页面时用的**完整地址**（含 `#boot=` 那一串启动参数）。
 *
 * <h2>为什么单独一个文件</h2>
 *
 * 两个地方要嵌产品：`pages/embed.vue`（壳内那条 `/org/embed/{product}/...` 路由）与
 * `pages/entry.vue`（入口页的每一个 Tab）。这段参数原先只写在 `embed.vue` 里，
 * 入口页要用就只剩两条路：抄一份，或者退回去走壳内那条路由（而那条路由正是入口页
 * 不能用它的原因 —— 见 `entry.vue` 的 `frameUrlOf`）。
 *
 * <p>抄一份的代价不是「多几行」：`#boot=` 是 iframe **首次**加载拿到登录态的唯一途径
 * （postMessage 那条通道只管续期，见 `components/ProductEmbed.vue`），漏掉任何一项
 * 的表现都是**静默**的 —— 少 `appearance` 是里外两种颜色，少 `tenantName` 是产品页
 * 头少一截。所以参数只留一处。
 *
 * <p>`sessionBoot()`（token / 租户 / 项目 / 角色）由 {@link productEmbedUrl} 自己拼，
 * 这里只补外壳才知道的那几样。
 */
export function shellProductUrl(
  product: string,
  path: string,
  frontendUrl: string,
  scope: ShellScope
): string {
  return productEmbedUrl(product, path, frontendUrl, {
    tenantName: currentTenant.value?.name ?? '',
    projectName: currentProject.value?.name ?? '',
    userId: app.currentUserId ?? '',
    // 现在这个壳（工作台还是项目）那套外观，推给被嵌的产品 —— 嵌入态下它不读自己那份，
    // 否则里外会拼成两种颜色。`scope` 的取值正好是外观作用域的两个子集。
    appearance: { ...appearanceOf(scope) },
  });
}
