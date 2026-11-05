<script setup lang="ts">
import type { Row } from '@/types/api'
import { administrationProgress, medicationRemainder } from '@/utils/orders'
defineProps<{ line: Row }>()
</script>
<template>
  <div v-if="line.item_type==='DRUG'" class="medication-progress">
    <div v-if="administrationProgress(line)">{{ administrationProgress(line) }}</div>
    <div>本次发药批量：{{ line.quantity_per_execution || line.quantity }} {{ line.unit }}</div>
    <div v-if="medicationRemainder(line)" class="plan-warning">{{ medicationRemainder(line) }}</div>
  </div>
</template>
<style scoped>
.medication-progress { margin-top: 6px; line-height: 1.6; overflow-wrap: anywhere; }
.plan-warning { color: #9a5b12; }
</style>
