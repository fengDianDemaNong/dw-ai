import { reactive, readonly } from 'vue';
import { listDataCatalogs } from '../services/api';

/**
 * 当前项目的默认数据目录名。
 *
 * <h2>为什么单独一个模块，不并进 stores/tenant.ts</h2>
 * `tenant.ts` 被 `utils/request.ts` 引用来注入租户请求头，而这里要调 `api.ts` 拉目录列表，
 * `api.ts → request.ts → tenant.ts` 已经成链。塞进 tenant.ts 就是循环依赖。
 * 单独一个模块只被页面引用，依赖方向是干净的。
 *
 * <h2>用途</h2>
 * 表全名在库里恒为三段 `目录.库.表`，但页面上把<b>默认目录</b>那一段隐去 ——
 * 绝大多数表都在默认目录下，每个名字都顶着一样的前缀纯属噪音。
 * 非默认目录仍显示完整全名，那才是需要区分的场合。
 * 转换在 `utils/common.ts` 的 `displayName()` 里，只作用于渲染。
 *
 * 与 `tenant.ts` 不同，这里<b>不做 localStorage 持久化</b>：默认目录是服务端状态，
 * 别的客户端可能刚改过，缓存下来只会展示一个过期的名字。每次启动重新拉。
 */
const state = reactive<{ defaultCatalog: string; loaded: boolean }>({
  defaultCatalog: '',
  loaded: false,
});

export const catalogState = readonly(state);

/**
 * 拉取当前项目的默认目录名。在 `main.ts` 里启动时调一次即可 ——
 * 切换租户/项目走 `stores/tenant.ts` 的 `switchTo`，它会整页重载，
 * 自然会重新走一遍启动流程，不需要额外挂监听。
 *
 * <p>失败时置空而不是抛出去：空字符串的含义是「不隐藏任何前缀」，
 * 页面退化成显示完整三段名 —— 信息更多而不是更少，是安全的降级。
 * 为了一个纯展示优化让整个页面加载失败不值得。
 */
export async function refreshDefaultCatalog(): Promise<void> {
  try {
    const list = await listDataCatalogs();
    state.defaultCatalog = list.find((c) => c.isDefault)?.name ?? '';
  } catch {
    state.defaultCatalog = '';
  } finally {
    state.loaded = true;
  }
}
