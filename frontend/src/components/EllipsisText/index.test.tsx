import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { CSSProperties, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import EllipsisText from '.'

vi.mock('antd', () => ({
  Typography: {
    Text: ({
      children,
      ellipsis,
      style,
      ...rest
    }: {
      children: ReactNode
      ellipsis: { suffix?: string; tooltip: boolean | string }
      style: CSSProperties
    }) => (
      <span
        {...rest}
        style={style}
        data-suffix={ellipsis.suffix ?? ''}
        data-tooltip={ellipsis.tooltip === false ? 'disabled' : String(ellipsis.tooltip)}
      >
        {children}
        {ellipsis.suffix}
      </span>
    )
  }
}))

describe('ellipsis text configuration', () => {
  it.each([0, -1, 5, 6])(
    'keeps the complete text when suffix count %s cannot split it',
    (suffixCount) => {
      render(
        <EllipsisText suffixCount={suffixCount} data-testid="text">
          abcde
        </EllipsisText>
      )
      expect(screen.getByTestId('text')).toHaveTextContent('abcde')
      expect(screen.getByTestId('text')).toHaveAttribute('data-suffix', '')
      expect(screen.getByTestId('text')).toHaveAttribute('data-tooltip', 'abcde')
    }
  )

  it('refreshes text, preserved suffix, tooltip and forwarded interaction independently', async () => {
    const oldClick = vi.fn()
    const nextClick = vi.fn()
    const view = render(
      <EllipsisText suffixCount={3} data-testid="text" onClick={oldClick}>
        commit-abc
      </EllipsisText>
    )
    expect(screen.getByTestId('text')).toHaveAttribute('data-suffix', 'abc')
    view.rerender(
      <EllipsisText suffixCount={3} data-testid="text" onClick={oldClick}>
        commit-def
      </EllipsisText>
    )
    expect(screen.getByTestId('text')).toHaveTextContent('commit-def')
    expect(screen.getByTestId('text')).toHaveAttribute('data-tooltip', 'commit-def')
    view.rerender(
      <EllipsisText suffixCount={4} data-testid="text" onClick={oldClick}>
        commit-def
      </EllipsisText>
    )
    expect(screen.getByTestId('text')).toHaveAttribute('data-suffix', '-def')
    view.rerender(
      <EllipsisText suffixCount={4} tooltip="完整的提交标识" data-testid="text" onClick={oldClick}>
        commit-def
      </EllipsisText>
    )
    expect(screen.getByTestId('text')).toHaveAttribute('data-tooltip', '完整的提交标识')
    view.rerender(
      <EllipsisText
        suffixCount={4}
        tooltip={false}
        style={{ maxWidth: 180, color: 'red' }}
        data-testid="text"
        aria-label="提交标识"
        onClick={nextClick}
      >
        commit-def
      </EllipsisText>
    )
    expect(screen.getByLabelText('提交标识')).toHaveAttribute('data-tooltip', 'disabled')
    expect(screen.getByTestId('text')).toHaveStyle({
      display: 'block',
      maxWidth: '180px',
      color: 'rgb(255, 0, 0)'
    })
    await userEvent.click(screen.getByTestId('text'))
    expect(nextClick).toHaveBeenCalledOnce()
    expect(oldClick).not.toHaveBeenCalled()
  })

  it('normalizes numeric content and allows a tooltip without a suffix', () => {
    render(
      <EllipsisText tooltip="计数" data-testid="number">
        {0}
      </EllipsisText>
    )
    expect(screen.getByTestId('number')).toHaveTextContent('0')
    expect(screen.getByTestId('number')).toHaveAttribute('data-tooltip', '计数')
  })
})
