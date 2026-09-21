import { ORG_PAGES } from './pages';
import type { NavGroup } from './nav';

export function buildSysNav(tenantAdmin = true): NavGroup[] {
  return [
    {
      title: '系统管理',
      items: [
        ...(tenantAdmin
          ? [
              { path: ORG_PAGES.users, label: '用户管理', icon: 'TeamOutlined' },
              { path: ORG_PAGES.roles, label: '角色管理', icon: 'SafetyCertificateOutlined' },
            ]
          : []),
        { path: ORG_PAGES.workbench, label: '项目管理', icon: 'AppstoreOutlined' },
        ...(tenantAdmin
          ? [
              { path: ORG_PAGES.knowledge, label: '知识库', icon: 'ReadOutlined' },
              { path: ORG_PAGES.settings, label: '设置', icon: 'SettingOutlined' },
            ]
          : []),
      ],
    },
  ];
}

export function buildAdminNav(): NavGroup[] {
  return [
    {
      title: '平台管理',
      items: [
        { path: ORG_PAGES.platformTenants, label: '租户', icon: 'BankOutlined' },
        { path: ORG_PAGES.platformServices, label: '服务注册', icon: 'ApiOutlined' },
        { path: ORG_PAGES.platformUsers, label: '平台用户', icon: 'TeamOutlined' },
        { path: ORG_PAGES.platformSettings, label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}
