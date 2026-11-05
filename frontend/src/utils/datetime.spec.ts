import { describe, expect, it } from 'vitest'
import { formatDateTime } from './datetime'

describe('formatDateTime', () => {
  it('converts UTC timestamps to Beijing time', () => {
    expect(formatDateTime('2026-07-26T07:59:38.438+00:00')).toBe('2026-07-26 15:59:38')
  })

  it('uses a readable placeholder for missing values', () => {
    expect(formatDateTime(null)).toBe('—')
  })

  it('preserves unrecognized values', () => {
    expect(formatDateTime('unknown')).toBe('unknown')
  })
})
