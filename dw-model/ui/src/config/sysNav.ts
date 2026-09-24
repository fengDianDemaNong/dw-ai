import type { NavGroup, NavItem } from './nav';
import { hasLocalAccounts } from './pages';
import { SYS_HOME } from './paths';

/**
 * 工作台菜单。
 *
 * <p>「用户管理」「角色管理」是本地账号体系的两页，只有 standard 有
 * （见 `config/pages.ts` 的 `hasLocalAccounts`）：standalone 没有人需要登录，
 * 管账号无处可用；multi 的账号在组织平台。路由本身保留，直接敲 URL 仍可进 ——
 * 收掉的只是入口，与 dw-lineage 那边处理「账号管理」的方式一致。
 */
export function buildSysNav(): NavGroup[] {
  const accountItems: NavItem[] = hasLocalAccounts()
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
