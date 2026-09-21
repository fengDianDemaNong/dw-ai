import type { NavGroup } from './nav';

export function buildSysNav(): NavGroup[] {
  return [
    {
      title: '系统管理',
      items: [
        { path: '/sys/users', label: '用户管理', icon: 'TeamOutlined' },
        { path: '/sys/roles', label: '角色管理', icon: 'SafetyCertificateOutlined' },
        { path: '/projects', label: '项目管理', icon: 'AppstoreOutlined' },
        { path: '/sys/modules', label: '模块管理', icon: 'AppstoreOutlined' },
        { path: '/sys/compute', label: '计算资源', icon: 'ClusterOutlined' },
        { path: '/sys/knowledge', label: '知识库', icon: 'ReadOutlined' },
        { path: '/sys/settings', label: '设置', icon: 'SettingOutlined' },
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
        { path: '/admin/services', label: '服务注册', icon: 'ApiOutlined' },
        { path: '/admin/users', label: '平台用户', icon: 'TeamOutlined' },
        { path: '/admin/settings', label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}
