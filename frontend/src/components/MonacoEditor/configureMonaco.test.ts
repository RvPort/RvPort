import { expect, it, vi } from 'vitest'

const boundary = vi.hoisted(() => ({
  configure: vi.fn(),
  defineTheme: vi.fn(),
  editor: vi.fn(),
  json: vi.fn()
}))
vi.mock('@monaco-editor/react', () => ({ loader: { config: boundary.configure } }))
vi.mock('monaco-editor/esm/vs/editor/editor.api.js', () => ({
  editor: { defineTheme: boundary.defineTheme }
}))
vi.mock('monaco-editor/esm/vs/editor/editor.worker?worker', () => ({
  default: class {
    constructor() {
      boundary.editor()
    }
  }
}))
vi.mock('monaco-editor/esm/vs/language/json/json.worker?worker', () => ({
  default: class {
    constructor() {
      boundary.json()
    }
  }
}))
vi.mock('monaco-editor/esm/vs/basic-languages/cpp/cpp.contribution.js', () => ({}))
vi.mock('monaco-editor/esm/vs/basic-languages/shell/shell.contribution.js', () => ({}))
vi.mock('monaco-editor/esm/vs/language/json/monaco.contribution.js', () => ({}))

it('routes JSON documents to their language worker and other documents to the editor worker without starting workers at import time', async () => {
  const host = self as typeof self & {
    MonacoEnvironment?: { getWorker: (moduleId: string, label: string) => Worker }
  }
  const previous = host.MonacoEnvironment
  try {
    await import('./configureMonaco')
    expect(boundary.json).not.toHaveBeenCalled()
    expect(boundary.editor).not.toHaveBeenCalled()
    const json = host.MonacoEnvironment!.getWorker('language', 'json')
    const cpp = host.MonacoEnvironment!.getWorker('language', 'cpp')
    const plain = host.MonacoEnvironment!.getWorker('editor', 'editorWorkerService')
    expect(boundary.json).toHaveBeenCalledOnce()
    expect(boundary.editor).toHaveBeenCalledTimes(2)
    expect(cpp).not.toBe(plain)
    expect(json).not.toBe(cpp)
    expect(boundary.configure).toHaveBeenCalledExactlyOnceWith({
      monaco: expect.objectContaining({
        editor: expect.objectContaining({ defineTheme: boundary.defineTheme })
      })
    })
    expect(boundary.defineTheme).toHaveBeenCalledWith(
      'rvport-light',
      expect.objectContaining({ base: 'vs', inherit: true })
    )
  } finally {
    host.MonacoEnvironment = previous
  }
})
