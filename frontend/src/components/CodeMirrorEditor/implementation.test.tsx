import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import CodeMirrorEditorImpl from './CodeMirrorEditorImpl'
import type { CodeMirrorEditorProps } from './types'

const editor = vi.hoisted(() => ({
  props: {} as ComponentProps<typeof import('@uiw/react-codemirror').default>
}))
vi.mock('@uiw/react-codemirror', () => ({
  default: (props: ComponentProps<typeof import('@uiw/react-codemirror').default>) => {
    editor.props = props
    return (
      <button
        onClick={() =>
          props.onChange?.('edited', {} as Parameters<NonNullable<typeof props.onChange>>[1])
        }
      >
        编辑内容
      </button>
    )
  }
}))

function highlightedOffsets() {
  const state = EditorState.create({ doc: editor.props.value, extensions: editor.props.extensions })
  const offsets: number[] = []
  for (const decoration of state.facet(EditorView.decorations)) {
    if (typeof decoration !== 'function')
      decoration.between(0, state.doc.length, (from) => {
        offsets.push(from)
      })
  }
  return offsets
}

describe('CodeMirror implementation contract', () => {
  it('filters invalid highlight lines, deduplicates and sorts them against the actual document', () => {
    render(
      <CodeMirrorEditorImpl
        value={'one\ntwo\nthree\nfour'}
        highlightedLineNumbers={[4, 2, 2, 0, -1, 1.5, 5, NaN]}
      />
    )
    expect(highlightedOffsets()).toEqual([4, 14])
    expect(editor.props.height).toBe('100%')
    expect(editor.props.readOnly).toBe(false)
    expect(editor.props.basicSetup).toMatchObject({
      foldGutter: false,
      highlightActiveLine: false,
      lineNumbers: true
    })
  })

  it('updates independent editor inputs and event ownership while preserving source and highlight semantics', () => {
    let props: CodeMirrorEditorProps = {
      value: 'first\nsecond',
      language: 'cpp',
      ariaLabel: '源码',
      highlightedLineNumbers: [2],
      onChange: vi.fn()
    }
    const view = render(<CodeMirrorEditorImpl {...props} />)
    const updates: Partial<CodeMirrorEditorProps>[] = [
      { language: 'json' },
      { language: 'shell' },
      { language: 'plaintext' },
      { ariaLabel: '更新后的源码' },
      { ariaLabel: undefined },
      { className: 'custom-editor' },
      { height: 320 },
      { height: '80vh' },
      { readOnly: true },
      { highlightedLineNumbers: [1] },
      { value: 'changed\nsecond' },
      { onChange: vi.fn() }
    ]
    for (const patch of updates) {
      props = { ...props, ...patch }
      view.rerender(<CodeMirrorEditorImpl {...props} />)
      expect(editor.props.value).toBe(props.value)
      expect(editor.props.readOnly).toBe(props.readOnly ?? false)
      expect(editor.props.height).toBe(
        typeof props.height === 'number' ? `${props.height}px` : (props.height ?? '100%')
      )
      expect(highlightedOffsets()).toEqual(props.highlightedLineNumbers?.[0] === 1 ? [0] : [6])
      const state = EditorState.create({ doc: props.value, extensions: editor.props.extensions })
      const attributes = Object.assign(
        {},
        ...state.facet(EditorView.contentAttributes).filter((value) => typeof value !== 'function')
      )
      expect(attributes['aria-label']).toBe(props.ariaLabel)
      expect(attributes.class).toContain('cm-lineWrapping')
    }
    expect(editor.props.className).toContain('custom-editor')
    fireEvent.click(screen.getByRole('button', { name: '编辑内容' }))
    expect(props.onChange).toHaveBeenCalledWith('edited', expect.anything())
    view.rerender(<CodeMirrorEditorImpl />)
    expect(editor.props.value).toBe('')
    expect(highlightedOffsets()).toEqual([])
  })
})
