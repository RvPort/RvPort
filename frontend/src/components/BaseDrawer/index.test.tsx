import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const drawer = vi.hoisted(() => ({ props: null as Record<string, unknown> | null }))

vi.mock('antd', () => ({
  Drawer: (props: Record<string, unknown>) => {
    drawer.props = props
    return <div>{props.children as ReactNode}</div>
  }
}))

import BaseDrawer, { type BaseDrawerProps } from '.'

describe('BaseDrawer', () => {
  it('updates independent presentation inputs without losing the open state or content', () => {
    let props: BaseDrawerProps = {
      visible: true,
      title: { title: '环境', subTitle: '连接配置' },
      children: '表单内容'
    }
    const { rerender } = render(<BaseDrawer {...props} />)
    const changes: Partial<BaseDrawerProps>[] = [
      { title: { title: '执行记录' } },
      { title: <strong>运行日志</strong> },
      { title: ['第一部分', '第二部分'] },
      { title: null },
      { className: 'custom-drawer' },
      { closable: { placement: 'start', disabled: true } },
      { placement: 'left' },
      { size: 720 },
      { footer: <button>保存</button> },
      { keyboard: false },
      { mask: false },
      { styles: { body: { padding: 8 } } },
      { styles: () => ({ body: { padding: 12 } }) },
      { visible: false }
    ]
    for (const change of changes) {
      props = { ...props, ...change }
      rerender(<BaseDrawer {...props} />)
      expect(screen.getByText('表单内容')).toBeInTheDocument()
      expect(drawer.props?.open).toBe(props.visible)
      expect(drawer.props?.placement).toBe(props.placement ?? 'right')
      expect(drawer.props?.size).toBe(props.size ?? 520)
      expect(drawer.props?.mask).toEqual(props.mask ?? { closable: true })
      expect(drawer.props?.keyboard).toBe(props.keyboard ?? true)
      expect(drawer.props?.closable).toEqual(props.closable ?? { placement: 'end' })
      expect(drawer.props?.footer).toBe(props.footer)
      const styleValue = drawer.props?.styles
      const resolved = typeof styleValue === 'function' ? styleValue({ props: {} }) : styleValue
      expect(resolved).toMatchObject({
        header: { flex: '0 0 auto' },
        body: props.styles
          ? { padding: typeof props.styles === 'function' ? 12 : 8 }
          : { minHeight: 0 }
      })
    }
    expect(drawer.props?.className).toContain('custom-drawer')
  })
  it('uses the shared modern close behavior by default', () => {
    render(
      <BaseDrawer visible onClose={vi.fn()}>
        Drawer 内容
      </BaseDrawer>
    )

    expect(screen.getByText('Drawer 内容')).toBeInTheDocument()
    expect(drawer.props?.closable).toEqual({ placement: 'end' })
    expect(drawer.props?.keyboard).toBe(true)
    expect(drawer.props?.mask).toEqual({ closable: true })
  })

  it('preserves an explicit close override for exceptional drawers', () => {
    render(
      <BaseDrawer closable={false} keyboard={false} mask={{ closable: false }} visible>
        受限 Drawer
      </BaseDrawer>
    )

    expect(drawer.props?.closable).toBe(false)
    expect(drawer.props?.keyboard).toBe(false)
    expect(drawer.props?.mask).toEqual({ closable: false })
  })
})
