import { fireEvent, render, screen } from '@testing-library/react'
import type { ModalProps } from 'antd'
import { describe, expect, it, vi } from 'vitest'

import BaseModal, { type BaseModalProps } from '.'

const state = vi.hoisted(() => ({ props: {} as ModalProps }))
vi.mock('antd', () => ({
  Modal: (props: ModalProps) => {
    state.props = props
    return (
      <div>
        {props.title}
        {props.children}
        <button onClick={props.onOk}>确认事件</button>
        <button onClick={props.onCancel}>取消事件</button>
      </div>
    )
  }
}))

describe('BaseModal adapter', () => {
  it('enforces busy event boundaries and restores independently supplied options', () => {
    const onOk = vi.fn(),
      onCancel = vi.fn()
    const { rerender } = render(<BaseModal visible busy onOk={onOk} onCancel={onCancel} />)
    fireEvent.click(screen.getByText('确认事件'))
    fireEvent.click(screen.getByText('取消事件'))
    expect(onOk).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
    expect(state.props).toMatchObject({
      closable: false,
      keyboard: false,
      mask: { closable: false },
      confirmLoading: true,
      okButtonProps: { disabled: true },
      cancelButtonProps: { disabled: true }
    })
    let props: BaseModalProps = {
      visible: true,
      onOk,
      onCancel,
      title: { title: '修改配置', subTitle: '提交前核对' }
    }
    const changes: Partial<BaseModalProps>[] = [
      { mask: { blur: true } },
      { maskClosable: false },
      { maskClosable: true },
      { mask: false },
      { closable: false },
      { keyboard: false },
      { confirmLoading: true },
      { okButtonProps: { disabled: true } },
      { cancelButtonProps: { disabled: true } },
      { title: { title: '新标题' } },
      { title: <strong>组件标题</strong> },
      { title: ['片段一', '片段二'] },
      { title: null },
      { styles: { body: { padding: 8 } } },
      { styles: () => ({ body: { padding: 12 } }) },
      { width: 800 },
      { footer: '底部操作' },
      { className: 'custom-modal' },
      { children: '表单保留' },
      { visible: false }
    ]
    for (const change of changes) {
      props = { ...props, ...change }
      rerender(<BaseModal {...props} />)
      expect(state.props.open).toBe(props.visible)
      expect(state.props.width).toBe(props.width ?? 640)
      expect(state.props.closable).toBe(props.closable)
      expect(state.props.keyboard).toBe(props.keyboard)
      expect(state.props.footer).toBe(props.footer)
      expect(state.props.okButtonProps?.disabled).toBe(props.okButtonProps?.disabled)
      expect(state.props.cancelButtonProps?.disabled).toBe(props.cancelButtonProps?.disabled)
      expect(state.props.mask).toEqual(
        props.mask === false
          ? false
          : {
              ...(typeof props.mask === 'object' ? props.mask : {}),
              ...(props.maskClosable !== undefined ? { closable: props.maskClosable } : {})
            }
      )
      const resolved =
        typeof state.props.styles === 'function'
          ? state.props.styles({ props: state.props })
          : state.props.styles
      expect(resolved).toMatchObject({
        container: { display: 'flex' },
        body: props.styles
          ? { padding: typeof props.styles === 'function' ? 12 : 8 }
          : { minHeight: 0 }
      })
    }
    fireEvent.click(screen.getByText('确认事件'))
    fireEvent.click(screen.getByText('取消事件'))
    expect(onOk).toHaveBeenCalledOnce()
    expect(onCancel).toHaveBeenCalledOnce()
    rerender(<BaseModal visible />)
    expect(() => fireEvent.click(screen.getByText('确认事件'))).not.toThrow()
    expect(() => fireEvent.click(screen.getByText('取消事件'))).not.toThrow()
  })
})
