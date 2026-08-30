import { computed, ref, type Ref } from 'vue'
import type { Row } from '@/types/api'

export function shortWardName(wardName: string, departmentName: string) {
  const name = wardName.trim()
  const department = departmentName.trim()
  return department && name.startsWith(department) ? name.slice(department.length).trim() || name : name
}

export function useBedFilters(beds: Ref<Row[]>) {
  const status = ref('')
  const departmentId = ref<number | ''>('')
  const wardName = ref('')
  // Build location options from all beds, not the status-filtered subset.
  const departments = computed(() => [...new Map(beds.value.map(bed => [bed.department_id,
    { id: bed.department_id, name: bed.department_name },
  ])).values()])
  // A ward label is an independent filter across departments, not a ward ID.
  const wards = computed(() => [...new Set(beds.value
    .map(bed => shortWardName(bed.ward_name, bed.department_name)))])
  const filtered = computed(() => beds.value.filter(bed =>
    (!status.value || bed.status === status.value)
    && (!departmentId.value || bed.department_id === departmentId.value)
    && (!wardName.value || shortWardName(bed.ward_name, bed.department_name) === wardName.value)))
  const hasFilters = computed(() => Boolean(status.value || departmentId.value || wardName.value))

  function resetFilters() {
    status.value = ''
    departmentId.value = ''
    wardName.value = ''
  }

  return { status, departmentId, wardName, departments, wards, filtered, hasFilters, resetFilters }
}
