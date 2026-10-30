import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useTableViewportHeight } from './useTableViewportHeight'

describe('table viewport measurement', () => {
  const originalObserver = window.ResizeObserver
  afterEach(() => {
    window.ResizeObserver = originalObserver
  })
  it('skips layout and listeners when disabled and cleans up on disable and unmount', () => {
    const root = document.createElement('div')
    const measure = vi
      .spyOn(root, 'getBoundingClientRect')
      .mockReturnValue({ height: 500 } as DOMRect)
    const observe = vi.fn(),
      disconnect = vi.fn()
    const construct = vi.fn()
    window.ResizeObserver = class {
      constructor() {
        construct()
      }
      observe = observe
      disconnect = disconnect
      unobserve = vi.fn()
    }
    const add = vi.spyOn(window, 'addEventListener'),
      remove = vi.spyOn(window, 'removeEventListener')
    const refs = {
      rootRef: { current: root },
      toolbarRef: { current: null },
      footerRef: { current: null },
      viewportRef: { current: null }
    }
    const { result, rerender, unmount } = renderHook(
      ({ enabled }) => useTableViewportHeight({ ...refs, enabled }),
      { initialProps: { enabled: false } }
    )
    expect(measure).not.toHaveBeenCalled()
    expect(construct).not.toHaveBeenCalled()
    expect(add.mock.calls.filter(([event]) => event === 'resize')).toHaveLength(0)
    rerender({ enabled: true })
    expect(result.current).toBe(500)
    expect(observe).toHaveBeenCalledWith(root)
    rerender({ enabled: false })
    expect(disconnect).toHaveBeenCalledOnce()
    const count = measure.mock.calls.length
    act(() => window.dispatchEvent(new Event('resize')))
    expect(measure).toHaveBeenCalledTimes(count)
    rerender({ enabled: true })
    expect(construct).toHaveBeenCalledTimes(2)
    unmount()
    expect(disconnect).toHaveBeenCalledTimes(2)
    expect(remove.mock.calls.filter(([event]) => event === 'resize')).toHaveLength(2)
  })

  it('preserves the measured height formula and stable updates', () => {
    const root = document.createElement('div'),
      toolbar = document.createElement('div'),
      footer = document.createElement('div'),
      viewport = document.createElement('div')
    root.style.rowGap = '10px'
    viewport.innerHTML = '<div class="ant-table-thead"></div><div class="ant-table-summary"></div>'
    for (const [node, height] of [
      [root, 500],
      [toolbar, 40],
      [footer, 30],
      [viewport.firstElementChild!, 32],
      [viewport.lastElementChild!, 20]
    ] as const)
      vi.spyOn(node, 'getBoundingClientRect').mockReturnValue({ height } as DOMRect)
    const { result } = renderHook(() =>
      useTableViewportHeight({
        rootRef: { current: root },
        toolbarRef: { current: toolbar },
        footerRef: { current: footer },
        viewportRef: { current: viewport }
      })
    )
    expect(result.current).toBe(358)
    act(() => window.dispatchEvent(new Event('resize')))
    expect(result.current).toBe(358)
  })

  it('falls back to resize events without ResizeObserver and releases the listener on unmount', () => {
    // Older browsers still need a usable table, including a viewport that becomes too small.
    window.ResizeObserver = undefined as unknown as typeof ResizeObserver
    const root = document.createElement('div'),
      toolbar = document.createElement('div'),
      footer = document.createElement('div'),
      viewport = document.createElement('div')
    viewport.innerHTML = '<div class="ant-table-thead"></div><div class="ant-table-summary"></div>'
    let rootHeight = 400
    const measure = vi
      .spyOn(root, 'getBoundingClientRect')
      .mockImplementation(() => ({ height: rootHeight }) as DOMRect)
    vi.spyOn(toolbar, 'getBoundingClientRect').mockReturnValue({ height: 50 } as DOMRect)
    const { result, unmount } = renderHook(() =>
      useTableViewportHeight({
        rootRef: { current: root },
        toolbarRef: { current: toolbar },
        footerRef: { current: footer },
        viewportRef: { current: viewport }
      })
    )
    expect(result.current).toBe(350)
    act(() => {
      rootHeight = 40
      window.dispatchEvent(new Event('resize'))
    })
    expect(result.current).toBeUndefined()
    act(() => {
      rootHeight = 500
      window.dispatchEvent(new Event('resize'))
    })
    expect(result.current).toBe(450)
    unmount()
    const count = measure.mock.calls.length
    act(() => window.dispatchEvent(new Event('resize')))
    expect(measure).toHaveBeenCalledTimes(count)
  })
})
