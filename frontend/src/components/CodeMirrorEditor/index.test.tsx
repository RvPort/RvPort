import { act, render, screen, waitFor } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

const implementation = vi.hoisted(() => {
  let releaseModule: () => void = () => undefined
  const moduleGate = new Promise<void>((resolve) => {
    releaseModule = resolve
  })

  return {
    factory: vi.fn(),
    moduleGate,
    releaseModule: () => releaseModule(),
    render: vi.fn()
  }
})

vi.mock('./CodeMirrorEditorImpl', async () => {
  implementation.factory()
  await implementation.moduleGate
  const { createElement } = await import('react')

  return {
    default: (props: Record<string, unknown>) => {
      implementation.render(props)
      return createElement('output', { 'data-testid': 'codemirror-implementation' })
    }
  }
})

import CodeMirrorEditor from '.'

describe('CodeMirrorEditor lazy boundary', () => {
  it('loads the implementation only after render and preserves the editor contract', async () => {
    expect(implementation.factory).not.toHaveBeenCalled()

    const onChange = vi.fn()
    render(
      <CodeMirrorEditor
        ariaLabel="分析证据"
        height={240}
        highlightedLineNumbers={[2, 4]}
        language="cpp"
        readOnly
        value="int main() {}"
        onChange={onChange}
      />
    )

    const fallback = screen.getByLabelText('分析证据')
    expect(fallback).toHaveAttribute('aria-busy', 'true')
    expect(fallback).toHaveStyle({ height: '240px' })
    await waitFor(() => expect(implementation.factory).toHaveBeenCalledTimes(1))

    await act(async () => implementation.releaseModule())

    expect(await screen.findByTestId('codemirror-implementation')).toBeInTheDocument()
    expect(implementation.render).toHaveBeenCalledWith(
      expect.objectContaining({
        ariaLabel: '分析证据',
        height: 240,
        highlightedLineNumbers: [2, 4],
        language: 'cpp',
        onChange,
        readOnly: true,
        value: 'int main() {}'
      })
    )
  })

  it('fills the parent height when height is omitted', async () => {
    implementation.render.mockClear()
    render(<CodeMirrorEditor ariaLabel="自适应代码编辑器" value="content" />)

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    expect(implementation.render).toHaveBeenLastCalledWith(
      expect.objectContaining({ height: '100%' })
    )
  })

  it('forwards independent prop changes after lazy loading without retaining an older editor callback', async () => {
    let props: ComponentProps<typeof CodeMirrorEditor> = {
      value: 'initial',
      ariaLabel: '源码',
      onChange: vi.fn()
    }
    const view = render(<CodeMirrorEditor {...props} />)
    await screen.findByTestId('codemirror-implementation')
    for (const patch of [
      { value: 'updated' },
      { ariaLabel: '更新源码' },
      { language: 'json' as const },
      { readOnly: true },
      { height: '60vh' },
      { className: 'custom-editor' },
      { highlightedLineNumbers: [2] },
      { onChange: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<CodeMirrorEditor {...props} />)
      expect(implementation.render).toHaveBeenLastCalledWith(
        expect.objectContaining({
          ...props,
          height: props.height ?? '100%',
          language: props.language ?? 'shell',
          readOnly: props.readOnly ?? false
        })
      )
    }
    const current = implementation.render.mock.calls.at(-1)?.[0] as ComponentProps<
      typeof CodeMirrorEditor
    >
    current.onChange?.('new content')
    expect(props.onChange).toHaveBeenCalledWith('new content')
  })
})
