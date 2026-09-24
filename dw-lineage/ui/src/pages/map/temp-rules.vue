<template>
  <div class="page">
    <PageHeader title="临时表规则" subtitle="命中的库表不进血缘图，也不入库">
      <template #actions>
        <Button @click="loadRules" :loading="ruleLoading">刷新</Button>
        <Button type="primary" class="bg-[#1677ff]" @click="openRuleCreate">新增规则</Button>
      </template>
      <template #help>
        <p>
          ETL 的 SQL 为了完成任务会建一堆中间临时表。命中规则的表在血缘图上会被
          <b>穿透</b>而不是删掉 —— 上下游仍然直接相连：
        </p>
        <pre class="hint-code">create table tmp.bb as select * from source_a;
insert into sink_b select * from tmp.bb;

包含临时表：source_a → tmp.bb → sink_b
不含临时表：source_a → sink_b        ← 不是断成两段</pre>
        <p>
          「数据地图 › SQL 解析」页的<b>「包含临时表」开关只影响看</b>；<b>保存一律过滤</b>，
          库里永远不会出现临时表。
        </p>
        <p>
          没有「正式表白名单」这种东西：正式表就是临时规则的补集。想把某张表排除在临时之外，
          改规则让它匹配不上，而不是另加一条白名单。
        </p>
      </template>
    </PageHeader>

    <div class="card card-flush">
      <Table
        :columns="ruleColumns"
        :data-source="rules"
        :loading="ruleLoading"
        row-key="id"
        size="small"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'catalogName'">
            <span v-if="record.catalogName" class="mono">{{ record.catalogName }}</span>
            <span v-else class="muted">全部目录</span>
          </template>

          <template v-else-if="column.key === 'target'">
            <Tag :color="record.target === 'SCHEMA' ? 'purple' : 'geekblue'">
              {{ record.target === 'SCHEMA' ? '库名' : '表名' }}
            </Tag>
          </template>

          <template v-else-if="column.key === 'matchType'">
            <Tag :color="record.matchType === 'GLOB' ? 'green' : 'orange'">
              {{ record.matchType === 'GLOB' ? '通配符' : '正则' }}
            </Tag>
          </template>

          <template v-else-if="column.key === 'pattern'">
            <span class="mono">{{ record.pattern }}</span>
          </template>

          <template v-else-if="column.key === 'enabled'">
            <Tag :color="record.enabled ? 'success' : 'default'">
              {{ record.enabled ? '启用' : '停用' }}
            </Tag>
          </template>

          <template v-else-if="column.key === 'action'">
            <Button type="link" size="small" @click="openRuleEdit(record as TempRule)">编辑</Button>
            <Popconfirm title="确定删除该规则？" @confirm="removeRule(record as TempRule)">
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </template>
        </template>
      </Table>
    </div>

    <!-- 试算。正则写错不报错、只是静默匹配不上，所以这个入口要显眼 -->
    <div class="card">
      <div class="card-title mb-2">规则试算</div>
      <p class="hint mb-2">
        输入一个库名或表名（也可以是 <code>目录.库.表</code> 全名），看看会不会被判为临时。
        <b>正则写错不会报错，只会默默匹配不上</b>，配完请在这里确认一次。
      </p>
      <div class="test-row">
        <Input
          v-model:value="testInput"
          placeholder="例如 tmp.bb、tmp_stage_01、ods.orders"
          style="max-width: 360px"
          @press-enter="runTest"
        />
        <Button type="primary" class="bg-[#1677ff]" :loading="testing" @click="runTest">试算</Button>
      </div>
      <Alert
        v-if="testResult"
        class="mt-3"
        :type="testResult.temp ? 'warning' : 'success'"
        show-icon
        :message="testResult.explanation"
      />
    </div>

    <Modal
      v-model:open="ruleModalOpen"
      :title="editingRuleId ? '编辑临时表规则' : '新增临时表规则'"
      :confirm-loading="ruleSaving"
      @ok="submitRule"
    >
      <Form :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <FormItem label="作用目录">
          <Select
            v-model:value="ruleForm.catalogName"
            allow-clear
            placeholder="留空表示对所有数据目录生效"
            :options="catalogOptions"
          />
        </FormItem>

        <FormItem label="作用对象" required>
          <RadioGroup v-model:value="ruleForm.target" button-style="solid">
            <RadioButton value="SCHEMA">库名</RadioButton>
            <RadioButton value="TABLE">表名</RadioButton>
          </RadioGroup>
          <div class="hint">
            选「库名」表示整个库都算临时（如 <code>tmp</code>、<code>test</code>）。
          </div>
        </FormItem>

        <FormItem label="匹配方式" required>
          <RadioGroup v-model:value="ruleForm.matchType" button-style="solid">
            <RadioButton value="GLOB">通配符</RadioButton>
            <RadioButton value="REGEX">正则</RadioButton>
          </RadioGroup>
          <!-- 这段提示不能省：tmp_* 在两种方式下含义完全相反，是最容易踩的坑 -->
          <div class="hint">
            <template v-if="ruleForm.matchType === 'GLOB'">
              <code>*</code> 匹配任意多个字符，<code>?</code> 匹配单个字符。
              <code>tmp_*</code> 能匹配 <code>tmp_abc</code> —— 多数情况下你要的是这个。
            </template>
            <template v-else>
              Java 正则，整串匹配。注意 <code>*</code> 修饰的是<b>前一个字符</b>：
              <code>tmp_*</code> 匹配的是 <code>tmp</code>、<code>tmp_</code>、<code>tmp__</code>，
              <b>匹配不到 <code>tmp_abc</code></b>。想匹配后者要写 <code>tmp_.*</code>。
            </template>
          </div>
        </FormItem>

        <FormItem label="匹配表达式" required>
          <Input v-model:value="ruleForm.pattern" placeholder="例如 tmp 或 tmp_*" />
        </FormItem>

        <FormItem label="启用">
          <Switch v-model:checked="ruleForm.enabled" />
        </FormItem>

        <FormItem label="说明">
          <Input v-model:value="ruleForm.description" />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue';
import {
  Alert, Button, Form, Input, Modal, Popconfirm, Radio, Select, Switch,
  Table, Tag, message,
} from 'ant-design-vue';
import PageHeader from '../../components/PageHeader/index.vue';
import {
  createTempRule, deleteTempRule, listDataCatalogs, listTempRules,
  testTempRule, updateTempRule,
} from '../../services/api';
import type {
  DataCatalog, TempMatchType, TempRule, TempRuleTarget, TempRuleTestResult,
} from '../../services/api';

const FormItem = Form.Item;
const RadioGroup = Radio.Group;
const RadioButton = Radio.Button;

const ruleColumns = [
  { title: '作用目录', key: 'catalogName', width: 160 },
  { title: '对象', key: 'target', width: 90 },
  { title: '匹配方式', key: 'matchType', width: 100 },
  { title: '匹配表达式', key: 'pattern', width: 200 },
  { title: '状态', key: 'enabled', width: 90 },
  { title: '说明', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'action', width: 140 },
];

const catalogs = ref<DataCatalog[]>([]);
const rules = ref<TempRule[]>([]);
const ruleLoading = ref(false);
const ruleSaving = ref(false);
const ruleModalOpen = ref(false);
const editingRuleId = ref<number | null>(null);

const testInput = ref('');
const testing = ref(false);
const testResult = ref<TempRuleTestResult | null>(null);

const ruleForm = reactive<{
  catalogName: string | undefined;
  target: TempRuleTarget;
  matchType: TempMatchType;
  pattern: string;
  enabled: boolean;
  description: string;
}>({
  catalogName: undefined,
  target: 'SCHEMA',
  matchType: 'GLOB',
  pattern: '',
  enabled: true,
  description: '',
});

/** 规则可以限定只对某个数据目录生效，所以这里要把目录列表也拉一份。 */
const catalogOptions = computed(() =>
  catalogs.value.map((c) => ({ label: c.name, value: c.name }))
);

async function loadRules() {
  ruleLoading.value = true;
  try {
    rules.value = await listTempRules();
  } catch (e) {
    message.error((e as Error).message || '加载规则失败');
  } finally {
    ruleLoading.value = false;
  }
}

function openRuleCreate() {
  editingRuleId.value = null;
  ruleForm.catalogName = undefined;
  ruleForm.target = 'SCHEMA';
  ruleForm.matchType = 'GLOB';
  ruleForm.pattern = '';
  ruleForm.enabled = true;
  ruleForm.description = '';
  ruleModalOpen.value = true;
}

function openRuleEdit(record: TempRule) {
  editingRuleId.value = record.id;
  ruleForm.catalogName = record.catalogName ?? undefined;
  ruleForm.target = record.target;
  ruleForm.matchType = record.matchType;
  ruleForm.pattern = record.pattern;
  ruleForm.enabled = record.enabled;
  ruleForm.description = record.description ?? '';
  ruleModalOpen.value = true;
}

async function submitRule() {
  if (!ruleForm.pattern.trim()) {
    message.warning('请填写匹配表达式');
    return;
  }
  ruleSaving.value = true;
  try {
    const payload = {
      catalogName: ruleForm.catalogName || undefined,
      target: ruleForm.target,
      matchType: ruleForm.matchType,
      pattern: ruleForm.pattern.trim(),
      enabled: ruleForm.enabled,
      description: ruleForm.description || undefined,
    };
    if (editingRuleId.value) {
      await updateTempRule(editingRuleId.value, payload);
    } else {
      await createTempRule(payload);
    }
    ruleModalOpen.value = false;
    await loadRules();
    // 规则一改，之前的试算结论就不作数了，留着会误导
    testResult.value = null;
    message.success('已保存');
  } catch (e) {
    message.error((e as Error).message || '保存失败');
  } finally {
    ruleSaving.value = false;
  }
}

async function removeRule(record: TempRule) {
  try {
    await deleteTempRule(record.id);
    await loadRules();
    testResult.value = null;
    message.success('已删除');
  } catch (e) {
    message.error((e as Error).message || '删除失败');
  }
}

async function runTest() {
  if (!testInput.value.trim()) {
    message.warning('请输入要试算的库名或表名');
    return;
  }
  testing.value = true;
  try {
    testResult.value = await testTempRule(testInput.value.trim());
  } catch (e) {
    testResult.value = null;
    message.error((e as Error).message || '试算失败');
  } finally {
    testing.value = false;
  }
}

onMounted(async () => {
  await Promise.all([
    loadRules(),
    listDataCatalogs()
      .then((list) => (catalogs.value = list))
      .catch(() => undefined),
  ]);
});
</script>

<style scoped>
.mb-2 { margin-bottom: 8px; }
.mt-3 { margin-top: 12px; }

.test-row {
  display: flex;
  gap: 8px;
}
</style>
