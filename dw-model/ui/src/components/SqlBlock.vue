<template>
  <pre class="sql"><slot />{{ text }}</pre>
</template>

<script setup lang="ts">
defineProps<{ text?: string }>();
</script>
