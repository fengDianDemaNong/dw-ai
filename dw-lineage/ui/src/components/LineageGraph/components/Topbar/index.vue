<template>
  <div class="g6-component-topbar-content">
    <!-- 先渲染字段血缘和完整血缘开关 -->
    <div
      v-for="(item, index) in options"
      :key="index"
      class="g6-component-topbar-item"
    >
      <span>{{ item.name }}</span>
      <Switch
        size="small"
        :checked="item.value"
        :disabled="item.disabled"
        @change="item.action"
      />
    </div>

    <!-- 然后渲染上游层级选择器 -->
    <div class="g6-component-topbar-item">
      <span>上游层级</span>
      <Select
        v-model:value="selectedLevel"
        size="small"
        :style="{ width: '60px' }"
        :placeholder="levelOptions.length ? undefined : '-'"
        @change="handleLevelChange"
      >
        <Option v-for="level in levelOptions" :key="level" :value="level">
          {{ level }}
        </Option>
        <Option v-if="!levelOptions.length" value="1" disabled>
          1
        </Option>
      </Select>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { computed, ref, watch } from 'vue';
import { Switch, Select } from 'ant-design-vue';
const { Option } = Select;

interface TopBarProps {
  // 这些属性是可选的，因为我们可以使用事件代替
  handleFieldLineage?: (checked: boolean) => void;
  handleWholeLineage?: (checked: boolean) => void;
  fieldChecked?: boolean;
  wholeChecked?: boolean;
  level?: number; // 当前层级
  maxLevel?: number; // 最大层级
  cfg?: any; // 配置信息
}

const props = withDefaults(defineProps<TopBarProps>(), {
  handleFieldLineage: undefined,
  handleWholeLineage: undefined,
  fieldChecked: true,
  wholeChecked: true,
  level: 0,
  maxLevel: 0,
  cfg: () => ({})
});
const emit = defineEmits(["handleFieldLineage", "handleWholeLineage", "levelChange"]);

// 层级选项（显示值）
const levelOptions = computed(() => {
  const options: number[] = [];
  const maxLevel = props.cfg.maxLevel || props.maxLevel;
  // 从1开始到maxLevel，因为层级是从1开始显示的
  if (maxLevel > 0) {
    for (let i = 1; i <= maxLevel; i++) {
      options.push(i);
    }
  }
  return options;
});

// 选中的层级（存储实际值）
const selectedLevel = ref(props.cfg.level || props.level || (levelOptions.value.length ? levelOptions.value[0] : "0"));

// 处理层级变化
const handleLevelChange = (value: unknown) => {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return;
  selectedLevel.value = n;
  emit("levelChange", n);
};

// 监听外部level变化
watch(() => props.level, (newLevel) => {
  selectedLevel.value = newLevel;
});

// 监听maxLevel变化，当maxLevel改变时更新选择框
watch([() => props.maxLevel, () => props.cfg.maxLevel], ([propMaxLevel, cfgMaxLevel]) => {
  const newMaxLevel = cfgMaxLevel || propMaxLevel;
  // 每当最大层级更新时，都将选择框设置为最新的最大层级
  if (newMaxLevel > 0) {
    selectedLevel.value = newMaxLevel;
    emit("levelChange", newMaxLevel);
  } else {
    // 如果没有有效的层级数据，设置为默认值"0"
    selectedLevel.value = "1";
  }
}, { immediate: true });

// 监听cfg的level变化
watch(() => props.cfg.level, (newLevel) => {
  if (newLevel !== undefined && newLevel > 0) {
    selectedLevel.value = newLevel;
  }
});

const handleFieldLineage = (checked: boolean | string | number) => {
  emit("handleFieldLineage", Boolean(checked));
}
const handleWholeLineage = (checked: boolean | string | number) => {
  emit("handleWholeLineage", Boolean(checked));
}

const options = computed(() => [
  {
    key: 'zoomOut',
    name: '字段血缘',
    value: props.fieldChecked,
    disabled: false,
    action: handleFieldLineage
  },
  {
    key: 'zoomIn',
    name: '完整血缘',
    value: props.wholeChecked,
    disabled: false,
    action: handleWholeLineage
  },
]);

</script>

<style scoped>
@import './index.css';
</style>
