<template>
  <a-alert
    v-if="!canWriteSpec"
    type="info"
    show-icon
    class="mb"
    message="规范只能由项目管理员新增、修改或删除。建模人员可以查看规范，作业请到建模中心。"
  />
</template>

<script setup lang="ts">
import { canWriteSpec } from '../stores/app';
</script>

<style scoped>
.mb {
  margin-bottom: 16px;
}
</style>
