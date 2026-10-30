import { describe, expect, it } from 'vitest'

import type { ProjectBuildBitcodeSummary } from '@/types/project'

import { getBitcodePresentation } from './bitcodePresentation'

const ready: ProjectBuildBitcodeSummary = {
  available: true,
  legacy: false,
  sourceIdentityDigest: null,
  compileContextDigest: null,
  bundleSha256: null,
  status: 'READY',
  reasonCode: 'NONE',
  schemaVersion: '2.0',
  artifactSetId: 'set-1',
  toolchainId: 'llvm-21.1.8-linux-x64',
  toolchainVersion: '21.1.8',
  plannedCount: 10,
  succeededCount: 10,
  failedCount: 0,
  skippedCount: 0,
  warningCount: 0,
  totalBytes: 1024,
  manifestArtifactId: 'manifest-1',
  bundleArtifactId: 'bundle-1'
}

describe('x86 build Bitcode presentation', () => {
  it('keeps build success separate from partial Bitcode generation', () => {
    expect(
      getBitcodePresentation({
        status: 'SUCCEEDED',
        bitcode: {
          ...ready,
          status: 'PARTIAL',
          reasonCode: 'TRANSLATION_UNIT_FAILED',
          succeededCount: 8,
          failedCount: 2
        }
      })
    ).toMatchObject({
      label: '部分生成',
      detail: '8/10 个编译单元成功，2 个失败；部分编译单元生成失败'
    })
  })

  it('reports a failed Bitcode artifact without changing the build status', () => {
    expect(
      getBitcodePresentation({
        status: 'SUCCEEDED',
        bitcode: {
          ...ready,
          available: false,
          status: 'FAILED',
          reasonCode: 'STORAGE_FAILED',
          plannedCount: 0,
          succeededCount: 0
        }
      })
    ).toMatchObject({ label: '生成失败', detail: '制品保存失败' })
  })

  it('explains unavailable and legacy records', () => {
    expect(
      getBitcodePresentation({
        status: 'SUCCEEDED',
        bitcode: {
          ...ready,
          available: false,
          status: 'UNSUPPORTED',
          reasonCode: 'COMPILE_DATABASE_UNAVAILABLE',
          plannedCount: 0,
          succeededCount: 0
        }
      })
    ).toMatchObject({ label: '未生成', detail: '没有可用的编译数据库' })
    expect(
      getBitcodePresentation({
        status: 'SUCCEEDED',
        bitcode: {
          ...ready,
          available: false,
          status: 'NOT_ATTEMPTED',
          reasonCode: 'LEGACY_RECORD'
        }
      })
    ).toMatchObject({ label: '未生成', detail: '历史构建没有 Bitcode 记录' })
  })

  it('explains legacy ready artifacts cannot feed specialized analysis', () => {
    expect(
      getBitcodePresentation({
        status: 'SUCCEEDED',
        bitcode: { ...ready, legacy: true, schemaVersion: '1.0' }
      }).detail
    ).toContain('重新构建生成 v2')
  })

  it('waits for an active build to finish before reporting its Bitcode result', () => {
    expect(getBitcodePresentation({ status: 'RUNNING', bitcode: ready })).toMatchObject({
      label: '待生成'
    })
  })
})

it('shows skipped units, empty successful artifacts and reason-free failed artifacts accurately', () => {
  expect(
    getBitcodePresentation({
      status: 'SUCCEEDED',
      bitcode: { ...ready, status: 'PARTIAL', succeededCount: 8, skippedCount: 2 }
    }).detail
  ).toBe('8/10 个编译单元成功，2 个跳过')
  expect(
    getBitcodePresentation({ status: 'SUCCEEDED', bitcode: { ...ready, plannedCount: 0 } }).detail
  ).toBe('Bitcode 已生成。')
  expect(
    getBitcodePresentation({
      status: 'SUCCEEDED',
      bitcode: { ...ready, status: 'FAILED', plannedCount: 0 }
    }).detail
  ).toBe('Bitcode 生成失败。')
  expect(
    getBitcodePresentation({
      status: 'SUCCEEDED',
      bitcode: { ...ready, status: 'NOT_ATTEMPTED', plannedCount: 0 }
    }).detail
  ).toBe('未尝试生成 Bitcode。')
})
