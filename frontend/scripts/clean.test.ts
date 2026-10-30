import { access, rm } from 'node:fs/promises'
import { resolve } from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

vi.mock('node:fs/promises', () => {
  const access = vi.fn(),
    rm = vi.fn()
  return { access, rm, default: { access, rm } }
})
// Vitest/jsdom rewrites import.meta.url to HTTP; keep the CLI filesystem root deterministic.
vi.mock('node:url', async (original) => {
  const actual = await original<typeof import('node:url')>()
  const fileURLToPath = () => process.cwd()
  return { ...actual, fileURLToPath, default: { ...actual, fileURLToPath } }
})
const originalArgv = process.argv
beforeEach(() => {
  vi.resetModules()
  process.argv = ['node', 'clean.mjs']
  vi.mocked(access).mockResolvedValue(undefined)
  vi.mocked(rm).mockResolvedValue(undefined)
  vi.spyOn(console, 'log').mockImplementation(() => {})
})
afterEach(() => {
  process.argv = originalArgv
})

it('removes build outputs while preserving dependencies by default', async () => {
  await import('./clean.mjs')
  expect(vi.mocked(rm).mock.calls).toEqual(
    ['dist', 'coverage', 'dist-ssr'].map((target) => [
      resolve(target),
      { force: true, recursive: true }
    ])
  )
  expect(console.log).toHaveBeenLastCalledWith('Cleanup complete: removed 3 targets.')
})
it('includes dependencies only with the explicit all flag', async () => {
  process.argv.push('--all')
  await import('./clean.mjs')
  for (const target of ['node_modules', '.pnpm-store'])
    expect(rm).toHaveBeenCalledWith(resolve(target), { force: true, recursive: true })
  expect(console.log).toHaveBeenLastCalledWith('Cleanup complete: removed 5 targets.')
})
it('handles an already clean workspace without attempting removals', async () => {
  vi.mocked(access).mockRejectedValue(Object.assign(new Error('missing'), { code: 'ENOENT' }))
  await import('./clean.mjs')
  expect(rm).not.toHaveBeenCalled()
  expect(console.log).toHaveBeenLastCalledWith('Cleanup complete: removed 0 targets.')
})
it('counts only existing outputs when just one directory remains', async () => {
  vi.mocked(access).mockImplementation(async (path) => {
    if (!String(path).endsWith('/dist')) throw new Error('missing')
  })
  await import('./clean.mjs')
  expect(rm).toHaveBeenCalledExactlyOnceWith(resolve('dist'), { force: true, recursive: true })
})
it('propagates a removal failure instead of reporting successful cleanup', async () => {
  vi.mocked(rm).mockRejectedValueOnce(
    Object.assign(new Error('permission denied'), { code: 'EACCES' })
  )
  await expect(import('./clean.mjs')).rejects.toThrow('permission denied')
  expect(
    vi
      .mocked(console.log)
      .mock.calls.some(([message]) => String(message).startsWith('Cleanup complete:'))
  ).toBe(false)
})
