import { ADMIN_HOME, SYS_HOME } from './paths';
import type { NavGroup } from './nav';

export function buildSysNav(): NavGroup[] {
  return [
    {
      title: '系统管理',
      items: [
        { path: '/workbench/users', label: '用户管理', icon: 'TeamOutlined' },
        { path: '/workbench/roles', label: '角色管理', icon: 'SafetyCertificateOutlined' },
        { path: SYS_HOME, label: '项目管理', icon: 'AppstoreOutlined' },
        { path: '/workbench/modules', label: '模块管理', icon: 'AppstoreOutlined' },
        { path: '/workbench/compute', label: '计算资源', icon: 'ClusterOutlined' },
        { path: '/workbench/knowledge', label: '知识库', icon: 'ReadOutlined' },
        { path: '/workbench/settings', label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}

export function buildAdminNav(): NavGroup[] {
  return [
    {
      title: '平台管理',
      items: [
        { path: ADMIN_HOME, label: '租户', icon: 'BankOutlined' },
        { path: '/platform/services', label: '服务注册', icon: 'ApiOutlined' },
        { path: '/platform/users', label: '平台用户', icon: 'TeamOutlined' },
        { path: '/platform/settings', label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}
