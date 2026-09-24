<template>
  <ConfigProvider :locale="zhCN">
    <router-view />
  </ConfigProvider>
</template>

<script lang="ts" setup>
/**
 * 应用外壳。
 *
 * 引入路由前，`pages/index.vue` 同时充当根组件与页面；现在它只是 `/` 这条路由的
 * 页面组件，根组件退化成这个只挂 router-view 的壳，页面之间不再互相耦合。
 *
 * 这里统一设中文 locale：不设的话 antd 会退回英文内置文案，
 * 满屏中文里蹦出「No data」「Select」这种，页面各处的空表格都是这个毛病。
 * 各页面自己那层 ConfigProvider 只覆盖 wave，没设 locale，会继承这里的值。
 */
import { ConfigProvider } from 'ant-design-vue';
// 路径不要写成 `ant-design-vue/es/locale/zh_CN`：构建时的组件解析插件会自动补 `es/` 前缀，
// 写全会变成 `es/es/locale/zh_CN` 而找不到文件（只在 build 阶段报错，dev 下不一定暴露）
import zhCN from 'ant-design-vue/locale/zh_CN';
</script>
