import type { ReviewFindingResponse } from '@sift/api-client'
import { describe, expect, it } from 'vitest'

import {
  countBySeverity,
  distinctFiles,
  groupFindingsByFile,
  lineRangeLabel,
  repositoryLabel,
  UNKNOWN_FILE,
} from '@/features/results/result-helpers'
import { findingFixtures } from '@/test/handlers'

describe('groupFindingsByFile', () => {
  it('groups by file, orders groups by their most severe finding and findings by severity/line', () => {
    const groups = groupFindingsByFile(findingFixtures)

    expect(groups.map((group) => group.file)).toEqual(['README.md', 'src/main/kotlin/Service.kt'])
    expect(groups[0]?.topSeverity).toBe('BLOCKER')
    expect(groups[1]?.findings.map((finding) => finding.id)).toEqual([1, 2])
  })

  it('orders findings of equal severity by start line and buckets missing files', () => {
    const findings: ReviewFindingResponse[] = [
      { id: 1, file: 'a.kt', severity: 'MINOR', startLine: 30 },
      { id: 2, file: 'a.kt', severity: 'MINOR', startLine: 5 },
      { id: 3, file: 'a.kt', severity: 'MINOR', startLine: null },
      { id: 4, severity: 'INFO' },
    ]
    const groups = groupFindingsByFile(findings)

    expect(groups.map((group) => group.file)).toEqual(['a.kt', UNKNOWN_FILE])
    expect(groups[0]?.findings.map((finding) => finding.id)).toEqual([2, 1, 3])
  })

  it('breaks ties between groups by file name', () => {
    const groups = groupFindingsByFile([
      { id: 1, file: 'z.kt', severity: 'INFO' },
      { id: 2, file: 'b.kt', severity: 'INFO' },
    ])
    expect(groups.map((group) => group.file)).toEqual(['b.kt', 'z.kt'])
  })
})

describe('finding helpers', () => {
  it('counts every severity, defaulting to zero', () => {
    expect(countBySeverity(findingFixtures)).toEqual({ BLOCKER: 1, MAJOR: 1, MINOR: 0, INFO: 1 })
    expect(countBySeverity([])).toEqual({ BLOCKER: 0, MAJOR: 0, MINOR: 0, INFO: 0 })
  })

  it('lists distinct files sorted', () => {
    expect(distinctFiles(findingFixtures)).toEqual(['README.md', 'src/main/kotlin/Service.kt'])
  })

  it('formats line ranges', () => {
    expect(lineRangeLabel({ startLine: 42, endLine: 48 })).toBe('L42–48')
    expect(lineRangeLabel({ startLine: 10, endLine: null })).toBe('L10')
    expect(lineRangeLabel({ startLine: 10, endLine: 10 })).toBe('L10')
    expect(lineRangeLabel({ startLine: null, endLine: 3 })).toBeUndefined()
  })

  it('shortens repository URLs', () => {
    expect(repositoryLabel('https://github.com/sift-dev/sift.git')).toBe('github.com/sift-dev/sift')
    expect(repositoryLabel(undefined)).toBe('—')
  })
})
