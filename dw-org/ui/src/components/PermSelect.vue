<template>
  <!--
    有词表 → 下拉：管理员从**产品自报**的权限词里选，选不到不存在的词。
    没词表 → 手填：老版本服务还没上报词表时，不让页面卡死（提示写在上方由调用方给）。
  -->
  <a-select
    v-if="options.length"
    :value="value"
    :options="choices"
    style="width: 100%"
    @update:value="(v: string) => emit('update:value', v ?? '')"
  />
  <a-input
    v-else
    :value="value"
    :placeholder="placeholder"
    @update:value="(v: string) => emit('update:value', v)"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { PermOption } from '../api/client';

/**
 * 权限词控件：**唯一**一处「怎么选权限词」的实现。
 *
 * <p>菜单管理页有三处要填权限词（手填表单、候选抽屉的列、以及角色页），各写一遍
 * 必然会漂 —— 而漂出来的症状是「同一件事在两个入口能填的词不一样」。
 *
 * <p>对外用 antd 的 `value` / `update:value` 而不是 Vue 默认的 `modelValue`：
 * 它是个表单控件，跟 `a-input`、`a-select` 摆在一起，调用处应当写成一样的
 * `v-model:value="..."`，不该因为「这个是我们自己写的」而换个写法。
 *
 * <h2>为什么选项来自 props 而不是在本组件里拉</h2>
 *
 * 词表是**按产品**的，而本组件不知道当前选的是哪个产品。而且同一页里多个产品
 * （候选抽屉会同时展示好几个）要共享拉取结果 —— 拉取与缓存归调用方，本组件只管渲染。
 *
 * <h2>空选项的含义是「产品没上报词表」，不是「这个产品不需要权限」</h2>
 *
 * 后者不存在：任何产品都至少有读权限词。所以空 = 拿不到，回落手填。
 */
const props = defineProps<{
  value: string;
  /** 产品自报的词表。空数组 = 没上报，回落手填。 */
  options: PermOption[];
  /** 手填回落的 placeholder。默认给一个真词，让管理员知道格式长什么样。 */
  placeholder?: string;
}>();

const emit = defineEmits<{ 'update:value': [string] }>();

/**
 * 下拉选项 = 「不判权」+ 产品词表 +（必要时）当前值。
 *
 * <p>补当前值是给**存量数据**用的：产品换了词表、或这条菜单是很早以前配的，
 * 它的词可能已经不在词表里了。不补的话下拉会显示成空 —— 管理员一保存就把
 * 权限悄悄清成了「不判权」，那等于把这个入口对所有角色放开，而且没有任何提示。
 */
const choices = computed(() => {
  const list: { value: string; label: string }[] = [
    { value: '', label: '不判权 —— 进得来就看得见' },
    ...props.options,
  ];
  if (props.value && !props.options.some((o) => o.value === props.value)) {
    list.push({ value: props.value, label: `${props.value}（产品未上报这个词）` });
  }
  return list;
});
</script>
