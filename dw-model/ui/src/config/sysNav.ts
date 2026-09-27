import type { NavGroup, NavItem } from './nav';
import { getRunMode } from './runtime';
import { SYS_HOME } from './paths';

/**
 * 工作台菜单。
 *
 * <p>五项：用户管理、角色管理、项目管理、知识库、设置（`0.1.4/spec/00-platform.md:67`）。
 *
 * <p>「用户管理」「角色管理」两页要<strong>两个条件同时成立</strong>才出现：
 *
 * <p><b>身份</b>（`tenantAdmin` 参数）：multi 下授权码只由租户管理员签发
 * （`03-identity-access.md:85`「平台授权码（仅多租户）」），知识库与设置也是
 * 租户级配置 —— 给普通成员看只会是一堆 403。规格两处都写这五项
 * 「<b>仅租户管理员可见</b>」（`00-platform.md:67`、`03-identity-access.md:106`）。
 *
 * <p><b>模式</b>：standalone 排除。它没有账号体系 —— 后端 `TenantFilter` 直接把它
 * 认成 admin，那几行演示账号是种子数据，不是给人管的对象；而组织账号也不是它。
 *
 * <p>这两页的**数据源随模式而异**：standard 是本地账号，multi 是组织平台
 * （`api.org.*`，经 `api/client.ts` 的 `isOrgScoped` 直连）。路由本身恒保留 ——
 * 收掉的只是入口，直接敲 URL 仍可进，与 dw-lineage 处理「账号管理」的方式一致。
 */
export function buildSysNav(tenantAdmin = true): NavGroup[] {
  const accountItems: NavItem[] =
    tenantAdmin && getRunMode() !== 'standalone'
      ? [
          { path: `${SYS_HOME}/users`, label: '用户管理', icon: 'TeamOutlined' },
          { path: `${SYS_HOME}/roles`, label: '角色管理', icon: 'SafetyCertificateOutlined' },
        ]
      : [];
  return [
    {
      title: '系统管理',
      items: [
        ...accountItems,
        { path: SYS_HOME, label: '项目管理', icon: 'AppstoreOutlined' },
        { path: `${SYS_HOME}/knowledge`, label: '知识库', icon: 'ReadOutlined' },
        { path: `${SYS_HOME}/settings`, label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}

/**
 * 平台管理菜单 —— <b>本 UI 没有 admin 壳，这一组是保留待用的死代码</b>。
 *
 * <p>路由表里没有任何 `meta.shell === 'admin'` 的条目（见 `router/index.ts`），
 * 所以 `SystemLayout` 的 `adminShell` 分支恒假、这份菜单没人调用；它引用的
 * `/admin`、`/admin/users`、`/admin/settings` 也全部未注册，点进去会落到兜底规则
 * 静默弹回。平台后台（租户管理、平台用户、平台外观）在 **dw-org/ui**。
 *
 * <p>不删：删了要连带拆 `SystemLayout` 的 `adminShell` / `ADMIN_HOME` 分支，
 * 那是独立一轮的事。留个记号，免得下次又有人以为它是活的。
 */
export function buildAdminNav(): NavGroup[] {
  return [
    {
      title: '平台管理',
      items: [
        { path: '/admin', label: '租户', icon: 'BankOutlined' },
        { path: '/admin/users', label: '平台用户', icon: 'TeamOutlined' },
        { path: '/admin/settings', label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}
