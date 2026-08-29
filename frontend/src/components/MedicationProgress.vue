<script setup lang="ts">
import type { Row } from '@/types/api'
import { administrationProgress, medicationRemainder } from '@/utils/orders'
defineProps<{ line: Row }>()
</script>
<template>
  <div v-if="line.item_type==='DRUG'" class="medication-progress">
    <div v-if="administrationProgress(line)">{{ administrationProgress(line) }}</div>
    <div v-else-if="line.order_type==='LONG_TERM'">已给药 {{ line.administration_count || 0 }} 次</div>
    <div v-if="line.excess_dispense_reason" class="plan-warning">超量发药原因：{{ line.excess_dispense_reason }}</div>
    <div v-if="medicationRemainder(line)" class="plan-warning">{{ medicationRemainder(line) }}余药退回需单独核对，不能通过继续给药或整笔冲正消除。</div>
  </div>
</template>
<style scoped>
.medication-progress { margin-top: 6px; line-height: 1.6; overflow-wrap: anywhere; }
.plan-warning { color: #9a5b12; }
</style>
