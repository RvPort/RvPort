import { act, render, screen, waitFor } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

const implementation = vi.hoisted(() => {
  let releaseModule: () => void = () => undefined
  const moduleGate = new Promise<void>((resolve) => {
    releaseModule = resolve
  })

  return {
    configureFactory: vi.fn(),
    editorFactory: vi.fn(),
    moduleGate,
    releaseModule: () => releaseModule(),
    render: vi.fn()
  }
})

vi.mock('./configureMonaco', () => {
  implementation.configureFactory()
  return {}
})

vi.mock('@monaco-editor/react', async () => {
  implementation.editorFactory()
  await implementation.moduleGate
  const { createElement } = await import('react')

  return {
    default: (props: Record<string, unknown>) => {
      implementation.render(props)
      return createElement('output', { 'data-testid': 'monaco-implementation' })
    }
  }
})

import MonacoEditor from '.'

describe('MonacoEditor lazy boundary', () => {
  it('loads Monaco only after render and preserves the editor contract', async () => {
    expect(implementation.configureFactory).not.toHaveBeenCalled()
    expect(implementation.editorFactory).not.toHaveBeenCalled()

    const onChange = vi.fn()
    render(
      <MonacoEditor
        ariaLabel="测试日志"
        height={360}
        language="shell"
        readOnly
        totalLines={1472}
        value="make test"
        onChange={onChange}
      />
    )

    const fallback = screen.getByLabelText('测试日志')
    expect(fallback).toHaveAttribute('aria-busy', 'true')
    expect(fallback).toHaveStyle({ height: '360px' })
    await waitFor(() => {
      expect(implementation.configureFactory).toHaveBeenCalledTimes(1)
      expect(implementation.editorFactory).toHaveBeenCalledTimes(1)
    })

    await act(async () => implementation.releaseModule())

    expect(await screen.findByTestId('monaco-implementation')).toBeInTheDocument()
    expect(implementation.render).toHaveBeenCalledWith(
      expect.objectContaining({
        height: '100%',
        language: 'shell',
        theme: 'rvport-light',
        value: 'make test'
      })
    )
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      options?: {
        ariaLabel?: string
        lineDecorationsWidth?: number
        lineNumbersMinChars?: number
        readOnly?: boolean
        renderLineHighlight?: string
      }
      onChange?: (value: string | undefined) => void
    }
    expect(editorProps.options).toEqual(
      expect.objectContaining({
        ariaLabel: '测试日志',
        lineDecorationsWidth: 16,
        lineNumbersMinChars: 4,
        readOnly: true,
        renderLineHighlight: 'none'
      })
    )
    editorProps.onChange?.('updated')
    expect(onChange).toHaveBeenCalledWith('updated')
  })

  it('fills the parent height when height is omitted', async () => {
    render(<MonacoEditor ariaLabel="自适应 Monaco 编辑器" value="content" />)

    const implementationElement = await screen.findByTestId('monaco-implementation')
    expect(implementationElement.parentElement).toHaveStyle({ height: '100%' })
  })

  it('updates independent editor options and normalizes empty edits using the current callback', async () => {
    let props: ComponentProps<typeof MonacoEditor> = {
      value: 'initial',
      onChange: vi.fn(),
      lineNumbers: [120],
      lineNumberOffset: 100
    }
    const view = render(<MonacoEditor {...props} />)
    await screen.findByTestId('monaco-implementation')
    for (const patch of [
      { ariaLabel: '更新源码' },
      { value: 'updated' },
      { language: 'json' },
      { readOnly: true },
      { wordWrap: 'off' as const },
      { theme: 'vs-dark' },
      { totalLines: 10000 },
      { className: 'custom-monaco' },
      { height: 400 },
      { onChange: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<MonacoEditor {...props} />)
      expect(implementation.render).toHaveBeenLastCalledWith(
        expect.objectContaining({
          value: props.value,
          language: props.language ?? 'shell',
          theme: props.theme ?? 'rvport-light',
          options: expect.objectContaining({
            ariaLabel: props.ariaLabel,
            readOnly: props.readOnly ?? false,
            wordWrap: props.wordWrap ?? 'on'
          })
        })
      )
    }
    const current = implementation.render.mock.calls.at(-1)?.[0] as {
      onChange: (value?: string) => void
      options: { lineNumbers: (line: number) => string; lineNumbersMinChars: number }
    }
    expect(current.options.lineNumbers(1)).toBe('120')
    expect(current.options.lineNumbers(2)).toBe('101')
    expect(current.options.lineNumbersMinChars).toBe(5)
    current.onChange(undefined)
    expect(props.onChange).toHaveBeenCalledWith('')
    view.rerender(<MonacoEditor value="read only" readOnly />)
    const readOnly = implementation.render.mock.calls.at(-1)?.[0] as {
      onChange: (value?: string) => void
    }
    expect(() => readOnly.onChange('ignored')).not.toThrow()
  })

  it('keeps Monaco current-line feedback for editable editors', async () => {
    implementation.render.mockClear()
    render(<MonacoEditor value="editable content" />)

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      options?: { renderLineHighlight?: string }
    }
    expect(editorProps.options?.renderLineHighlight).toBe('line')
  })

  it('loads earlier content at most once per request and throttle window', async () => {
    implementation.render.mockClear()
    let releaseLoad: () => void = () => undefined
    const pendingLoad = new Promise<void>((resolve) => {
      releaseLoad = resolve
    })
    const onLoadEarlierContent = vi.fn(() => pendingLoad)
    render(
      <MonacoEditor
        hasEarlierContent
        value="latest output"
        onLoadEarlierContent={onLoadEarlierContent}
      />
    )

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
    }
    let onScrollChange: ((event: { scrollTopChanged: boolean }) => void) | undefined
    const editor = {
      deltaDecorations: vi.fn(() => []),
      getScrollHeight: vi.fn(() => 1000),
      getScrollTop: vi.fn(() => 0),
      onDidScrollChange: vi.fn((listener) => {
        onScrollChange = listener
        return { dispose: vi.fn() }
      }),
      revealLineInCenter: vi.fn(),
      setScrollTop: vi.fn()
    }
    const now = vi
      .spyOn(Date, 'now')
      .mockReturnValueOnce(1000)
      .mockReturnValueOnce(1001)
      .mockReturnValueOnce(1600)

    editorProps.onMount?.(editor, { Range: vi.fn() })
    onScrollChange?.({ scrollTopChanged: true })
    onScrollChange?.({ scrollTopChanged: true })
    expect(onLoadEarlierContent).toHaveBeenCalledTimes(1)

    releaseLoad()
    await pendingLoad
    await Promise.resolve()
    onScrollChange?.({ scrollTopChanged: true })
    expect(onLoadEarlierContent).toHaveBeenCalledTimes(2)
    now.mockRestore()
  })

  it('renders the highlighted line marker in Monaco reserved decorations lane', async () => {
    implementation.render.mockClear()
    render(<MonacoEditor highlightedLineNumbers={[2]} value={'first\nsecond'} />)

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
    }
    const deltaDecorations = vi.fn(() => [])
    const editor = {
      deltaDecorations,
      getScrollHeight: vi.fn(() => 100),
      getScrollTop: vi.fn(() => 0),
      onDidScrollChange: vi.fn(() => ({ dispose: vi.fn() })),
      revealLineInCenter: vi.fn(),
      setScrollTop: vi.fn()
    }

    editorProps.onMount?.(editor, { Range: vi.fn() })

    const decorationCalls = deltaDecorations.mock.calls as unknown as Array<
      [unknown, Array<{ options: Record<string, unknown> }>]
    >
    const decorations = decorationCalls.at(-1)?.[1] ?? []
    expect(decorations[0]?.options).toMatchObject({
      isWholeLine: true,
      linesDecorationsClassName: expect.stringMatching(/highlightedLineDecoration/)
    })
    expect(decorations[0]?.options).not.toHaveProperty('marginClassName')
  })

  it('reveals the highlighted line again when its navigation key changes', async () => {
    implementation.render.mockClear()
    const highlightedLineNumbers = [2]
    const { rerender } = render(
      <MonacoEditor
        highlightedLineNumbers={highlightedLineNumbers}
        revealLineKey={1}
        value={'first\nsecond'}
      />
    )

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
    }
    const revealLineInCenter = vi.fn()
    const editor = {
      deltaDecorations: vi.fn(() => []),
      getScrollHeight: vi.fn(() => 100),
      getScrollTop: vi.fn(() => 0),
      onDidScrollChange: vi.fn(() => ({ dispose: vi.fn() })),
      revealLineInCenter,
      setScrollTop: vi.fn()
    }

    editorProps.onMount?.(editor, { Range: vi.fn() })
    expect(revealLineInCenter).toHaveBeenCalledWith(2)

    rerender(
      <MonacoEditor
        highlightedLineNumbers={highlightedLineNumbers}
        revealLineKey={2}
        value={'first\nsecond'}
      />
    )

    await waitFor(() => expect(revealLineInCenter).toHaveBeenCalledTimes(2))
  })

  it('can reveal an explicit line without highlighting it', async () => {
    implementation.render.mockClear()
    render(<MonacoEditor revealLineKey="initial" revealLineNumber={1} value={'first\nsecond'} />)

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
    }
    const revealLineInCenter = vi.fn()
    const editor = {
      deltaDecorations: vi.fn(() => []),
      getScrollHeight: vi.fn(() => 100),
      getScrollTop: vi.fn(() => 0),
      onDidScrollChange: vi.fn(() => ({ dispose: vi.fn() })),
      revealLineInCenter,
      setScrollTop: vi.fn()
    }

    editorProps.onMount?.(editor, { Range: vi.fn() })

    expect(revealLineInCenter).toHaveBeenCalledWith(1)
  })

  it('renders an inline decoration for a highlighted source range', async () => {
    implementation.render.mockClear()
    render(
      <MonacoEditor
        highlightedRanges={[
          { endColumn: 12, endLineNumber: 2, startColumn: 4, startLineNumber: 2 }
        ]}
        value={'first\nsecond'}
      />
    )

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
    }
    const deltaDecorations = vi.fn(() => [])
    const editor = {
      deltaDecorations,
      getScrollHeight: vi.fn(() => 100),
      getScrollTop: vi.fn(() => 0),
      onDidScrollChange: vi.fn(() => ({ dispose: vi.fn() })),
      revealLineInCenter: vi.fn(),
      setScrollTop: vi.fn()
    }
    const Range = vi.fn()

    editorProps.onMount?.(editor, { Range })

    expect(Range).toHaveBeenCalledWith(2, 4, 2, 12)
    const decorationCalls = deltaDecorations.mock.calls as unknown as Array<
      [unknown, Array<{ options: Record<string, unknown> }>]
    >
    const decorations = decorationCalls.at(-1)?.[1] ?? []
    expect(decorations[0]?.options.inlineClassName).toEqual(
      expect.stringMatching(/highlightedRange/)
    )
  })

  it('renders interactive source gaps outside the editor text model and removes stale zones', async () => {
    implementation.render.mockClear()
    const onLoad = vi.fn()
    const { rerender } = render(
      <MonacoEditor
        sourceGaps={[
          {
            afterLineNumber: 1,
            endLine: 4131,
            id: '1434-4131',
            lineCount: 2698,
            loadable: true,
            onLoad,
            startLine: 1434
          }
        ]}
        value={'first code block\nsecond code block'}
      />
    )

    await waitFor(() => expect(implementation.render).toHaveBeenCalled())
    const editorProps = implementation.render.mock.calls.at(-1)?.[0] as {
      onMount?: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
      value?: string
    }
    const removeZone = vi.fn()
    let zone:
      | {
          afterLineNumber: number
          domNode: HTMLElement
          heightInPx: number
          onDomNodeTop: (top: number) => void
          suppressMouseDown: boolean
        }
      | undefined
    let overlayWidget:
      | {
          getDomNode: () => HTMLElement
          getPosition: () => { preference: { left: number; top: number } }
        }
      | undefined
    const editor = {
      addOverlayWidget: vi.fn((widget) => {
        overlayWidget = widget
      }),
      changeViewZones: vi.fn((change) =>
        change({
          addZone: vi.fn((nextZone) => {
            zone = nextZone
            return 'zone-1'
          }),
          removeZone
        })
      ),
      deltaDecorations: vi.fn(() => []),
      getLayoutInfo: vi.fn(() => ({ contentLeft: 64 })),
      getScrollHeight: vi.fn(() => 100),
      getScrollTop: vi.fn(() => 0),
      layoutOverlayWidget: vi.fn(),
      onDidScrollChange: vi.fn(() => ({ dispose: vi.fn() })),
      removeOverlayWidget: vi.fn(),
      revealLineInCenter: vi.fn(),
      setScrollTop: vi.fn()
    }

    editorProps.onMount?.(editor, { Range: vi.fn() })

    expect(editorProps.value).not.toContain('已省略')
    expect(zone?.afterLineNumber).toBe(1)
    expect(zone?.suppressMouseDown).toBe(true)
    expect(zone?.domNode.querySelector('button')).toBeNull()
    expect(overlayWidget?.getDomNode().closest('[aria-hidden="true"]')).toBeNull()
    expect(overlayWidget?.getDomNode()).toHaveTextContent('已省略 2698 行 · 第 1434–4131 行')
    zone?.onDomNodeTop(120)
    expect(overlayWidget?.getPosition()).toEqual({ preference: { left: 64, top: 120 } })
    expect(editor.layoutOverlayWidget).toHaveBeenCalledWith(overlayWidget)
    const loadButton = overlayWidget?.getDomNode().querySelector('button')
    expect(loadButton?.style.pointerEvents).toBe('auto')
    loadButton?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    loadButton?.click()
    expect(onLoad).toHaveBeenCalledOnce()

    rerender(<MonacoEditor sourceGaps={[]} value={'first code block\nsecond code block'} />)
    await waitFor(() => expect(removeZone).toHaveBeenCalledWith('zone-1'))
    expect(editor.removeOverlayWidget).toHaveBeenCalledWith(overlayWidget)
  })
})

it('restores the viewport after prepending old logs and then follows new output when requested', async () => {
  let height = 1000
  let scroll!: (event: { scrollTopChanged: boolean }) => void
  const load = vi.fn(async () => {})
  const view = render(
    <MonacoEditor value="current" hasEarlierContent onLoadEarlierContent={load} />
  )
  await screen.findByTestId('monaco-implementation')
  const props = implementation.render.mock.calls.at(-1)![0] as {
    onMount: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
  }
  const editor = {
    deltaDecorations: vi.fn(() => []),
    getScrollHeight: () => height,
    getScrollTop: () => 10,
    setScrollTop: vi.fn(),
    onDidScrollChange: (listener: typeof scroll) => {
      scroll = listener
      return { dispose: vi.fn() }
    },
    revealLineInCenter: vi.fn()
  }
  props.onMount(editor, { Range: vi.fn() })
  await act(async () => scroll({ scrollTopChanged: true }))
  expect(load).toHaveBeenCalledOnce()
  height = 1600
  view.rerender(
    <MonacoEditor value={'older\ncurrent'} hasEarlierContent onLoadEarlierContent={load} />
  )
  expect(editor.setScrollTop).toHaveBeenLastCalledWith(610)
  view.rerender(<MonacoEditor value={'older\ncurrent\nnew'} scrollToBottomOnValueChange />)
  expect(editor.setScrollTop).toHaveBeenLastCalledWith(1600)
})

it('updates gap retry/loading controls, hides offscreen zones and removes zones on unmount', async () => {
  const load = vi.fn()
  const gap = {
    id: 'gap',
    afterLineNumber: 1,
    startLine: 2,
    endLine: 10,
    lineCount: 9,
    loadable: true,
    onLoad: load
  }
  const view = render(<MonacoEditor value="line" sourceGaps={[{ ...gap, loading: true }]} />)
  await screen.findByTestId('monaco-implementation')
  const props = implementation.render.mock.calls.at(-1)![0] as {
    onMount: (editor: Record<string, unknown>, monaco: Record<string, unknown>) => void
  }
  const host = document.createElement('div')
  document.body.append(host)
  let position!: (top: number) => void
  const removeZone = vi.fn()
  const editor = {
    deltaDecorations: vi.fn(() => []),
    getScrollHeight: () => 100,
    getScrollTop: () => 0,
    setScrollTop: vi.fn(),
    onDidScrollChange: () => ({ dispose: vi.fn() }),
    revealLineInCenter: vi.fn(),
    getLayoutInfo: () => ({ contentLeft: 10 }),
    addOverlayWidget: (widget: { getDomNode: () => HTMLElement }) =>
      host.append(widget.getDomNode()),
    removeOverlayWidget: (widget: { getDomNode: () => HTMLElement }) =>
      widget.getDomNode().remove(),
    layoutOverlayWidget: vi.fn(),
    changeViewZones: (change: (accessor: object) => void) =>
      change({
        removeZone,
        addZone: (zone: { onDomNodeTop: (top: number) => void }) => {
          position = zone.onDomNodeTop
          return 'zone'
        }
      })
  }
  props.onMount(editor, { Range: vi.fn() })
  expect(screen.getByRole('button', { name: '加载第 2–10 行' })).toBeDisabled()
  expect(screen.getByText('加载中…')).toBeInTheDocument()
  view.rerender(<MonacoEditor value="line" sourceGaps={[{ ...gap, error: true }]} />)
  expect(screen.getByText('重新加载')).toBeInTheDocument()
  const button = screen.getByRole('button', { name: '加载第 2–10 行' })
  button.click()
  expect(load).toHaveBeenCalledOnce()
  position(-20000)
  expect(host.firstElementChild).toHaveStyle({ visibility: 'hidden' })
  position(10)
  expect(host.firstElementChild).toHaveStyle({ visibility: 'visible' })
  view.rerender(<MonacoEditor value="line" sourceGaps={[{ ...gap, onLoad: undefined }]} />)
  expect(screen.queryByRole('button', { name: '加载第 2–10 行' })).not.toBeInTheDocument()
  expect(host).toHaveTextContent('已省略 9 行')
  const before = removeZone.mock.calls.length
  view.unmount()
  expect(removeZone.mock.calls.length).toBe(before + 1)
  expect(host).toBeEmptyDOMElement()
  host.remove()
})
