import {
  ApiOutlined,
  CodeOutlined,
  DashboardOutlined,
  FilterOutlined,
  FolderOutlined,
  PartitionOutlined,
  ProjectOutlined,
  SearchOutlined,
  SettingOutlined,
  TableOutlined,
  TeamOutlined,
} from '@ant-design/icons-vue';

/**
 * 菜单图标表。
 *
 * 只按名字取用到的这几个，而不是 `import * as icons`：后者会把整个图标库
 * （上千个组件）打进包里。侧边栏和顶部菜单栏共用这一份。
 *
 * 键名必须与 `config/nav.ts` 里各项的 `icon` 对上。
 */
export const navIcons: Record<string, any> = {
  ApiOutlined,
  CodeOutlined,
  DashboardOutlined,
  FilterOutlined,
  FolderOutlined,
  PartitionOutlined,
  ProjectOutlined,
  SearchOutlined,
  SettingOutlined,
  TableOutlined,
  TeamOutlined,
};
