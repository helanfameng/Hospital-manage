import { describe, expect, it } from 'vitest'
import { effectScope, ref, type Ref } from 'vue'
import type { Row } from '@/types/api'
import { shortWardName, useBedFilters } from './useBedFilters'

const makeBeds = () => [
  { id: 1, department_id: 1, department_name: '心内科', ward_id: 11, ward_name: '心内科一病区', status: 'AVAILABLE' },
  { id: 2, department_id: 1, department_name: '心内科', ward_id: 11, ward_name: '心内科一病区', status: 'OCCUPIED' },
  { id: 3, department_id: 1, department_name: '心内科', ward_id: 12, ward_name: '心内科二病区', status: 'MAINTENANCE' },
  { id: 4, department_id: 2, department_name: '骨科', ward_id: 21, ward_name: '骨科一病区', status: 'AVAILABLE' },
  { id: 5, department_id: 2, department_name: '骨科', ward_id: 21, ward_name: '骨科一病区', status: 'DISABLED' },
]

function check(run: (filters: ReturnType<typeof useBedFilters>, beds: Ref<Row[]>) => void) {
  const scope = effectScope()
  try { scope.run(() => { const beds = ref(makeBeds()); run(useBedFilters(beds), beds) }) }
  finally { scope.stop() }
}

describe('床位位置和状态筛选', () => {
  it('默认显示全部床位，病区按简称去重且不显示科室组合', () => check(filters => {
    expect(filters.filtered.value).toHaveLength(5)
    expect(filters.departments.value).toHaveLength(2)
    expect(filters.wards.value).toEqual(['一病区', '二病区'])
  }))

  it('科室、病区、状态使用交集，状态切换不清除位置条件', () => check(filters => {
    filters.departmentId.value = 1
    filters.wardName.value = '一病区'
    filters.status.value = 'AVAILABLE'
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([1])
    filters.status.value = 'OCCUPIED'
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([2])
    expect(filters.departmentId.value).toBe(1)
    expect(filters.wardName.value).toBe('一病区')
    filters.status.value = ''
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([1, 2])
  }))

  it('状态和科室不改变病区选项，切换科室保留病区和状态', () => check(filters => {
    filters.departmentId.value = 1
    filters.wardName.value = '一病区'
    filters.status.value = 'MAINTENANCE'
    expect(filters.filtered.value).toEqual([])
    expect(filters.wards.value).toEqual(['一病区', '二病区'])
    filters.departmentId.value = 2
    expect(filters.wardName.value).toBe('一病区')
    expect(filters.status.value).toBe('MAINTENANCE')
    expect(filters.wards.value).toEqual(['一病区', '二病区'])
    filters.departmentId.value = ''
    expect(filters.wards.value).toHaveLength(2)
  }))

  it('未指定科室时匹配所有科室的同名病区，可继续叠加科室筛选', () => check(filters => {
    filters.wardName.value = '一病区'
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([1, 2, 4, 5])
    filters.departmentId.value = 2
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([4, 5])
    filters.departmentId.value = ''
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([1, 2, 4, 5])
    filters.wardName.value = '二病区'
    expect(filters.filtered.value.map(bed => bed.id)).toEqual([3])
    filters.departmentId.value = 2
    expect(filters.filtered.value).toEqual([])
  }))

  it('刷新床位数据保留筛选，重置后恢复全部', () => check((filters, beds) => {
    filters.departmentId.value = 1
    filters.wardName.value = '一病区'
    filters.status.value = 'AVAILABLE'
    beds.value = makeBeds().map(bed => bed.id === 1 ? { ...bed, status: 'MAINTENANCE' } : bed)
    expect(filters.filtered.value).toHaveLength(0)
    expect(filters.hasFilters.value).toBe(true)
    filters.resetFilters()
    expect(filters.hasFilters.value).toBe(false)
    expect(filters.filtered.value).toHaveLength(5)
  }))
})

describe('病区名称简写', () => {
  it('只移除开头重复的科室名称，保留自定义名称', () => {
    expect(shortWardName('心内科一病区', '心内科')).toBe('一病区')
    expect(shortWardName('心内科二病区', '心内科')).toBe('二病区')
    expect(shortWardName('一病区', '心内科')).toBe('一病区')
    expect(shortWardName('东楼心内科病区', '心内科')).toBe('东楼心内科病区')
    expect(shortWardName('心内科', '心内科')).toBe('心内科')
  })
})
