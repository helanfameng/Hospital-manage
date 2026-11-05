<script setup lang="ts">
import { enumLabel, orderStatusLabels, orderStatusTagTypes } from '@/utils/labels'
defineProps<{ status?: string }>()
</script>
<template>
  <el-tag :type="orderStatusTagTypes[status || ''] || 'info'" :class="{ 'order-in-progress': status==='IN_PROGRESS' }">{{ enumLabel(orderStatusLabels,status) }}</el-tag>
</template>
<style scoped>
.order-in-progress {
  --el-tag-text-color: #7c3aed;
  --el-tag-bg-color: #f5f0ff;
  --el-tag-border-color: #ddd0fc;
}
</style>
