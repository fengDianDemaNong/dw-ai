<template>
  <!-- 解析工具条。以前它兼着整站导航和品牌标识，用的是 container mx-auto + 阴影
       那套「页头」样式；导航搬走之后它只是一条工具条，跟着页面统一成筛选条的样子 -->
  <header class="parse-toolbar text-gray-600">
    <div class="parse-toolbar-inner">
      <!-- 品牌标识在侧边栏顶部，这里不再重复一份 -->
      <nav class="parse-controls">
        <Tooltip placement="bottom" :title="sourceScopeHint">
          <Select
            v-model:value="parseMode"
            class="parse-mode-select"
            :options="parseModeOptions"
            :loading="sourceLoading"
          />
        </Tooltip>
        <div>
          <label v-show="!isExternal" for="dbTypeSelect">数据库类型：</label>
          <Select
            v-show="!isExternal"
            v-model:value="selectedValue"
            class="db-type-select"
            @change="handleChange"
            :loading="!dbTypeOptions.length"
            :options="dbTypeOptions"
            id="dbTypeSelect"
          />
        </div>

        <!-- Gravitino 才有 metalake / catalog 两级；dbx 的目标连接在配置页里指定 -->
        <template v-if="isGravitinoSource">
          <label for="dbTypeSelect">Matelake：</label>
          <Select
            v-model:value="selectedMatelake"
            class="external-select"
            :options="matelakeOptions"
            :loading="matelakeLoading"
            placeholder="选择Matelake"
            style="width: 80px; margin-right: 12px;"
            @change="handleMatelakeChange"
          />
          <label for="dbTypeSelect">Catalog：</label>
          <Select
            v-model:value="selectedCatalog"
            class="external-select"
            :options="catalogOptions"
            :loading="catalogLoading"
            placeholder="选择Catalog"
            :disabled="!selectedMatelake"
            style="width: 100px; margin-right: 12px;"
          />
        </template>
        <div v-show="!isExternal" class="create-table-switch">
          <Tooltip placement="bottom" title="使用建表语句获取表信息">
            <Switch v-model:checked="isCreateTable" size="small" />
          </Tooltip>
        </div>
        <div class="create-table-switch">
          <!-- 默认关：默认看到的图就是保存进库的那张。
               保存一律过滤临时表，不受这个开关影响 -->
          <Tooltip
            placement="bottom"
            title="包含临时表。规则在「数据目录」配置页里维护；无论开关如何，保存时都会过滤掉临时表"
          >
            <Switch v-model:checked="includeTemp" size="small" checked-children="临" un-checked-children="临" />
          </Tooltip>
        </div>
        <Input
          v-model:value="columnName"
          placeholder="字段段名"
          style="width: 150px"
          @keyup.enter="handleParseSql"
        />
        <Tooltip placement="bottom" title="解析血缘">
          <Button
            type="primary"
            class="bg-[#1677ff]"
            :style="{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '4px 8px'
            }"
            @click="handleParseSql"
          >
            <RocketOutlined />
          </Button>
        </Tooltip>
        <Tooltip placement="bottom" title="把解析结果保存到数据库，之后可在「数据地图 › 血缘」页的血缘图里查看">
          <Button
            :loading="saving"
            :style="{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '4px 8px',
              marginLeft: '6px'
            }"
            @click="$emit('handleSaveLineage')"
          >
            <SaveOutlined />
          </Button>
        </Tooltip>
      </nav>
      <!-- 主题、水印、缩略图都搬去「设置 › 基本信息」了。
           它们本来就不该藏在解析页的一个齿轮里，而且当时只存在内存中，刷新即失 -->
      <div class="ml-2 hidden items-center rounded-md ring-1 ring-gray-900/5 lg:flex">
        <HeaderButton
          :isActive="layout === 'vertical'"
          label="Switch to vertical split layout"
          @click="$emit('layoutChange', 'vertical')"
        >
          <path
            d="M12 3h9a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-9"
            fill="none"
          />
          <path
            d="M3 17V5a2 2 0 0 1 2-2h7a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a2 2 0 0 1-2-2Z"
          />
        </HeaderButton>
        <HeaderButton
          :isActive="layout === 'editor'"
          label="Switch to preview-only layout"
          @click="$emit('layoutChange', 'editor')"
        >
          <path
            fill="none"
            d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
          />
        </HeaderButton>
        <HeaderButton
          :isActive="layout === 'preview'"
          label="Switch to preview-only layout"
          @click="$emit('layoutChange', 'preview')"
        >
          <path
            d="M23 17V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2Z"
            fill="none"
          />
        </HeaderButton>
      </div>
    </div>
  </header>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import metadata from '../../config/metadata';
import HeaderButton from './headerButton.vue';
import { Button, Form, Input, Select, Switch, Tooltip } from 'ant-design-vue';
const FormItem = Form.Item;
import { RocketOutlined, SaveOutlined } from '@ant-design/icons-vue';
// ColorPicker 组件已不再使用
import { getDbTypes, getMatelakes, getCatalogs, listMetadataSources } from '../../services/api';
import type { MetadataSource } from '../../services/api';

interface HeaderProps {
  /** 列名 */
  columnName?: string;
  /** 编辑器主题色 */
  /** 布局方式 */
  layout: string;
  /** 水印文字 */
  // 高亮颜色设置已移除
  /** 是否显示缩略图 */
  /** 是否为创建表操作 */
  isCreateTable: boolean;
  /** 血缘图上是否保留临时表 */
  includeTemp: boolean;
  /** 保存血缘中 */
  saving?: boolean;
}



// 使用props中的columnName初始值
const columnName = computed({
  get: () => props.columnName || '',
  set: (val) => emit('update:columnName', val)
});
const props = defineProps<HeaderProps>();
const emit = defineEmits([
  'layoutChange',
  'handleParseSql',
  'update:columnName',
  // 高亮颜色事件已移除
  'dbTypeChange',
  'update:isCreateTable',
  'update:includeTemp',
  'parseModeChange',
  'update:selectedMatelake',
  'update:selectedCatalog',
  'sourceChange',
  'handleSaveLineage',
]);


/**
 * 元数据来源。
 *
 * 取值为 `noMatedata`（不接外部元数据）或 `source:<id>`——后者对应
 *「设置 › 元数据服务」页面上配置的某条服务。此前这里是写死的两个选项，
 * 且「外部元数据」硬连唯一一个 yml 里配的 Gravitino。
 */
const parseMode = ref('noMatedata');
const selectedValue = ref('');
const selectedMatelake = ref('');
const selectedCatalog = ref('');
const matelakeOptions = ref<{value: string; label: string}[]>([]);
const catalogOptions = ref<{value: string; label: string}[]>([]);
const matelakeLoading = ref(false);
const catalogLoading = ref(false);

const metadataSources = ref<MetadataSource[]>([]);
const sourceLoading = ref(false);

const NO_METADATA = 'noMatedata';

const parseModeOptions = computed(() => [
  { value: NO_METADATA, label: '无元数据' },
  ...metadataSources.value.map((s) => ({
    value: `source:${s.id}`,
    label: `${s.name}（${s.type === 'GRAVITINO' ? 'Gravitino' : 'dbx'}）`,
  })),
]);

const isExternal = computed(() => parseMode.value.startsWith('source:'));

const selectedSource = computed<MetadataSource | null>(() => {
  if (!isExternal.value) return null;
  const id = Number(parseMode.value.slice('source:'.length));
  return metadataSources.value.find((s) => s.id === id) ?? null;
});

const selectedSourceId = computed(() => selectedSource.value?.id);

const isGravitinoSource = computed(() => selectedSource.value?.type === 'GRAVITINO');

/** 鼠标悬停时告诉用户这个来源能干什么、不能干什么，省得对 Hive 表选了 dbx。 */
const sourceScopeHint = computed(() => {
  if (!selectedSource.value) {
    return '只用 SQL 里的建表语句与结构推断，不查外部元数据服务';
  }
  return selectedSource.value.applicableScope;
});

// 拉取已启用的元数据服务，供来源下拉使用
const fetchMetadataSources = async () => {
  sourceLoading.value = true;
  try {
    const all = await listMetadataSources();
    metadataSources.value = all.filter((s) => s.enabled);
  } catch (error) {
    console.error('Failed to fetch metadata sources:', error);
    metadataSources.value = [];
  } finally {
    sourceLoading.value = false;
  }
};

// 获取matelakes数据
const fetchMatelakes = async () => {
  matelakeLoading.value = true;
  try {
    const response = await getMatelakes(selectedSourceId.value);

    // 直接使用响应数组，假设接口返回的就是数组
    matelakeOptions.value = response.map(item => ({
      value: String(item),
      label: String(item)
    }));
  } catch (error) {
    console.error('Failed to fetch matelakes:', error);
    matelakeOptions.value = [];
  } finally {
    matelakeLoading.value = false;
  }
};

// 获取catalogs数据
const fetchCatalogs = async (matelake: string) => {
  catalogLoading.value = true;
  try {
    // 确保参数名称与API函数中的一致
    const catalogs = await getCatalogs(matelake, selectedSourceId.value);
    const list = Array.isArray(catalogs) ? catalogs : [];
    catalogOptions.value = list.map((item) => ({
      value: String(item),
      label: String(item),
    }));
  } catch (error) {
    console.error('Failed to fetch catalogs:', error);
    catalogOptions.value = [];
  } finally {
    catalogLoading.value = false;
  }
};

// 监听元数据来源变化
watch(parseMode, async (newMode) => {
  // 换来源后原来的 metalake / catalog 一定不再适用，先清干净
  selectedMatelake.value = '';
  selectedCatalog.value = '';
  matelakeOptions.value = [];
  catalogOptions.value = [];

  if (isExternal.value) {
    isCreateTable.value = false;
    // 只有 Gravitino 需要在这里选两级；dbx 的目标连接写在配置的 extraConfig 里
    if (isGravitinoSource.value) {
      await fetchMatelakes();
    }
  }

  emit('parseModeChange', newMode);
  emit('sourceChange', {
    sourceId: selectedSourceId.value,
    type: selectedSource.value?.type,
  });
});

// 处理matelake选择变化
const handleMatelakeChange = async (value: unknown) => {
  if (typeof value !== 'string') return;
  emit('update:selectedMatelake', value);
  selectedCatalog.value = '';
  catalogOptions.value = [];

  if (value) {
    await fetchCatalogs(value);
  }
};

// 监听matelake选择变化
watch(selectedMatelake, (newMatelake) => {
  emit('update:selectedMatelake', newMatelake);
});

// 监听catalog选择变化
watch(selectedCatalog, (newCatalog) => {
  emit('update:selectedCatalog', newCatalog);
});

const dbTypeOptions = ref<{ value: string; label: string; }[]>([]);

const includeTemp = computed({
  get: () => props.includeTemp,
  set: (val) => emit('update:includeTemp', val),
});

const isCreateTable = computed({
  get: () => props.isCreateTable,
  set: (val) => emit('update:isCreateTable', val)
});

// 获取数据库类型列表
const fetchDbTypes = async () => {
  try {
    const response = await getDbTypes();
    if (Array.isArray(response)) {
      dbTypeOptions.value = response.map(type => ({
        value: type,
        label: type
      }));
      // 设置默认选中值
      if (dbTypeOptions.value.length > 0) {
        selectedValue.value = dbTypeOptions.value[0].value;
      }
    }
  } catch (error) {
    console.error('Failed to fetch database types:', error);
  }
};

// 组件挂载时获取数据库类型与可用的元数据服务
onMounted(() => {
  fetchDbTypes().then(() => {
    // 确保在获取到数据库类型后，立即触发默认值的变更
    if (selectedValue.value) {
      emit('dbTypeChange', selectedValue.value);
    }
  });

  fetchMetadataSources();
});

const handleChange = (value: unknown) => {
  if (typeof value !== 'string') return;
  emit('dbTypeChange', value);
};

const handleParseSql = () => {
  emit('handleParseSql');
  emit('update:columnName', columnName.value);
};

</script>

<style scoped>
.parse-toolbar {
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.parse-toolbar-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  flex-wrap: wrap;
}

/* 控件之间的间距交给 gap，不再用 space-x-2 那种相邻选择器 ——
   中间有 v-show 隐藏的控件时它会算错 */
.parse-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-right: auto;
  font-size: 14px;
}

.parse-mode-select {
  width: 180px;
  margin-right: 12px;
}

.parse-mode-select :deep(.ant-select-selector) {
  height: 32px !important;
  padding: 0 11px !important;
}

.parse-mode-select :deep(.ant-select-selection-item) {
  line-height: 30px !important;
  font-size: 14px;
}

.external-select {
  width: 50px;
  margin-right: 12px;
}

.external-select :deep(.ant-select-selector) {
  height: 32px !important;
  padding: 0 11px !important;
}

.external-select :deep(.ant-select-selection-item) {
  line-height: 30px !important;
  font-size: 14px;
}

.db-type-select {
  width: 100px;
  margin-right: 12px;
}

.db-type-select :deep(.ant-select-selector) {
  height: 32px !important;
  padding: 0 11px !important;
}

.db-type-select :deep(.ant-select-selection-item) {
  line-height: 30px !important;
  font-size: 14px;
}

.create-table-switch {
  display: inline-flex;
  align-items: center;
  margin-right: 12px;
  cursor: pointer;
}

/* 优化开关样式 */
.create-table-switch :deep(.ant-switch) {
  min-width: 28px;
  height: 16px;
  background-color: rgba(0, 0, 0, 0.25);
}

.create-table-switch :deep(.ant-switch-checked) {
  background-color: #1890ff;
}

.create-table-switch :deep(.ant-switch-handle) {
  width: 12px;
  height: 12px;
  top: 2px;
}

.create-table-switch :deep(.ant-switch-checked .ant-switch-handle) {
  left: calc(100% - 14px);
}

.create-table-switch :deep(.ant-switch:not(.ant-switch-checked) .ant-switch-handle) {
  left: 2px;
}

/* 添加悬停效果 */
.create-table-switch:hover :deep(.ant-switch:not(.ant-switch-checked)) {
  background-color: rgba(0, 0, 0, 0.45);
}

.create-table-switch:hover :deep(.ant-switch-checked) {
  background-color: #40a9ff;
}

/* 添加过渡效果 */
.create-table-switch :deep(.ant-switch),
.create-table-switch :deep(.ant-switch-handle) {
  transition: all 0.2s ease-in-out;
}

.setting-label {
  display: inline-block;
  width: 70px;
  text-align: right;
}

/* 确保设置面板中的标签对齐 */
.settings-form :deep(.w-24) {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  min-width: 96px;
}
.settings-form {
  width: 100%;
  min-width: 420px;
  padding: 0 12px;
}

.custom-save-button {
  background-color: #1890ff !important;
  border-color: #1890ff !important;
  color: #ffffff !important;
}

.custom-save-button:hover {
  background-color: #40a9ff !important;
  border-color: #40a9ff !important;
}

.custom-save-button:active {
  background-color: #096dd9 !important;
  border-color: #096dd9 !important;
}

.settings-form :deep(.ant-form-item) {
  margin-bottom: 20px;
}

.settings-form :deep(.ant-form-item-label) {
  text-align: left;
  padding-bottom: 8px;
}

.settings-form :deep(.ant-form-item-control-input-content) {
  display: flex;
  align-items: center;
}

.settings-form :deep(.ant-form-item:last-child) {
  margin-bottom: 12px;
  margin-top: 40px;
}

.settings-form :deep(.ant-select),
.settings-form :deep(.ant-input) {
  width: 100%;
}

.settings-form :deep(.ant-form-item-control-input) {
  min-height: 32px;
}

.settings-form .flex {
  gap: 12px;
}

/* 调整标签和输入框的间距 */
.settings-form :deep(.ant-form-item-label) > label {
  height: 32px;
  padding-right: 16px;
}

/* 自定义开关颜色 */
:deep(.custom-switch.ant-switch) {
  background-color: rgba(0, 0, 0, 0.25) !important;
}

:deep(.custom-switch.ant-switch.ant-switch-checked) {
  background-color: #1890ff !important;
}
</style>