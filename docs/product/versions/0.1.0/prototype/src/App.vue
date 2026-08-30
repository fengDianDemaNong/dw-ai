<template>
  <a-config-provider :locale="zhCN" :theme="theme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

dayjs.locale('zh-cn');

const theme = {
  token: {
    colorPrimary: '#0e7490',
    colorInfo: '#0e7490',
    borderRadius: 8,
    fontFamily:
      "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
};
</script>
