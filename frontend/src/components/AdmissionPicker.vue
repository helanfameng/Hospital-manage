<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { getData } from '@/api/client'
import type { PageResult, Row } from '@/types/api'

const props = withDefaults(defineProps<{ modelValue?: number; kind?: 'patient' | 'admission'; status?: string; disabled?: boolean }>(), { kind: 'admission', status: 'ALL' })
const emit = defineEmits<{ 'update:modelValue': [value: number | undefined]; change: [row: Row | undefined] }>()
const rows = ref<Row[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const keyword = ref('')
let generation = 0
const key = (row: Row) => Number(props.kind === 'patient' ? row.id : row.admission_id ?? row.id)
const label = (row: Row) => props.kind === 'patient' ? `${row.name} · ${row.patient_no}` : `${row.patient_name} · ${row.admission_no}`
async function load(append = false) {
  const ticket = ++generation
  loading.value = true
  try {
    const result = await getData<PageResult<Row>>(props.kind === 'patient' ? '/patients' : '/admissions', { keyword: keyword.value, page: page.value, size: 20, status: props.status })
    if (ticket !== generation) return
    rows.value = append ? [...rows.value, ...result.items] : result.items
    total.value = result.total
  } finally { if (ticket === generation) loading.value = false }
}
async function search(value: string) { keyword.value = value; page.value = 1; await load() }
async function more() { page.value += 1; await load(true) }
function change(value: number | undefined) {
  emit('update:modelValue', value)
  emit('change', rows.value.find(row => key(row) === Number(value)))
}
watch(() => props.status, () => search(''))
onMounted(() => search(''))
</script>
<template>
  <el-select :model-value="modelValue" :disabled="disabled" filterable remote clearable :remote-method="search" :loading="loading"
    :placeholder="kind === 'patient' ? '搜索姓名、患者号或证件号' : '搜索患者姓名或住院号'" style="width:100%" @change="change">
    <el-option v-for="row in rows" :key="key(row)" :label="label(row)" :value="key(row)" :disabled="kind === 'patient' && !row.active" />
    <template #footer><el-button v-if="rows.length < total" text :loading="loading" @click.stop.prevent="more">加载更多（已显示 {{ rows.length }}/{{ total }}）</el-button><span v-else class="muted">共 {{ total }} 条</span></template>
  </el-select>
</template>
