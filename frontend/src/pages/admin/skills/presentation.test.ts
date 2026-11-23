import { describe, expect, it } from 'vitest'

import type { SkillStatus } from '@/types/admin/skills'

import {
  describeUpdateResult,
  didRevisionChange,
  parseChangedFiles,
  presentSkillStatus,
  shortRevision
} from './presentation'

function status(overrides: Partial<SkillStatus> = {}): SkillStatus {
  return {
    commitsBehind: 0,
    currentRevision: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    diffStatSummary: '',
    remoteRevision: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    skills: ['riscv-migrate'],
    ...overrides
  }
}

describe('admin skills presentation', () => {
  it('shortens a revision and shows a placeholder when it is missing', () => {
    expect(shortRevision('d0422e9614759b26ccf53594d025cebd520067c3')).toBe('d0422e961475')
    expect(shortRevision('abc')).toBe('abc')
    expect(shortRevision(null)).toBe('--')
  })

  it('parses changed file paths and drops the trailing summary line', () => {
    const diffStat =
      ' riscv-migrate/SKILL.md | 30 ++++----\n riscv-migrate/agents/worker.md |  2 -\n 2 files changed, 16 insertions(+), 16 deletions(-)\n'
    expect(parseChangedFiles(diffStat)).toEqual([
      'riscv-migrate/SKILL.md',
      'riscv-migrate/agents/worker.md'
    ])
    expect(parseChangedFiles('')).toEqual([])
    expect(parseChangedFiles(undefined)).toEqual([])
  })

  it('reports that the skills are up to date', () => {
    const presented = presentSkillStatus(status())
    expect(presented.hasUpdate).toBe(false)
    expect(presented.summary).toBe('技能已是最新版本')
    expect(presented.changedFiles).toEqual([])
  })

  it('reports available updates with the commit and file counts', () => {
    const presented = presentSkillStatus(
      status({
        commitsBehind: 3,
        diffStatSummary: ' a.md | 1 +\n b.md | 2 -\n 2 files changed\n',
        remoteRevision: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
      })
    )
    expect(presented.hasUpdate).toBe(true)
    expect(presented.summary).toBe('远端有 3 个新提交，涉及 2 个文件')
    expect(presented.changedFiles).toEqual(['a.md', 'b.md'])
  })

  it('does not claim an update before the status is loaded', () => {
    expect(presentSkillStatus(null).hasUpdate).toBe(false)
  })

  it('describes an update result', () => {
    expect(
      describeUpdateResult({
        afterRevision: 'b',
        beforeRevision: 'a',
        commitsChanged: 2,
        diffStatLines: [],
        filesChanged: 5
      })
    ).toBe('技能已更新：2 个提交，5 个文件')
    expect(
      describeUpdateResult({
        afterRevision: 'a',
        beforeRevision: 'a',
        commitsChanged: 0,
        diffStatLines: [],
        filesChanged: 0
      })
    ).toBe('技能已是最新版本，无需更新')
  })

  it('detects whether the revision actually moved', () => {
    expect(
      didRevisionChange({
        afterRevision: 'b',
        beforeRevision: 'a',
        commitsChanged: 1,
        diffStatLines: [],
        filesChanged: 1
      })
    ).toBe(true)
    expect(
      didRevisionChange({
        afterRevision: 'a',
        beforeRevision: 'a',
        commitsChanged: 0,
        diffStatLines: [],
        filesChanged: 0
      })
    ).toBe(false)
  })
})
