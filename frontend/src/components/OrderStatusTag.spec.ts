import { describe, expect, it } from 'vitest'
import { createApp } from 'vue'
import OrderStatusTag from './OrderStatusTag.vue'

describe('医嘱状态标签', () => {
  it.each([
    ['DRAFT', '草稿', 'info'],
    ['SUBMITTED', '已提交', 'primary'],
    ['IN_PROGRESS', '执行中', 'primary'],
    ['COMPLETED', '已完成', 'success'],
    ['STOPPED', '已停止', 'warning'],
    ['CANCELLED', '已撤销', 'danger'],
  ])('%s使用统一的状态颜色和文字', (status, label, type) => {
    const root = document.createElement('div')
    const app = createApp(OrderStatusTag, { status })
    app.component('ElTag', { props: ['type'], template: '<span :data-type="type"><slot /></span>' })
    try {
      app.mount(root)
      expect(root.textContent).toBe(label)
      expect(root.firstElementChild?.getAttribute('data-type')).toBe(type)
      expect(root.firstElementChild?.classList.contains('order-in-progress')).toBe(status === 'IN_PROGRESS')
    } finally { app.unmount() }
  })
})
