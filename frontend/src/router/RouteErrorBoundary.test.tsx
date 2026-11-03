import { useRouteError } from 'react-router'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import RouteErrorBoundary from './RouteErrorBoundary'
import { getRouteErrorPresentation } from './routeErrorPresentation'

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>()

  return {
    ...actual,
    useRouteError: vi.fn()
  }
})

const useRouteErrorMock = vi.mocked(useRouteError)

describe('RouteErrorBoundary', () => {
  beforeEach(() => {
    useRouteErrorMock.mockReturnValue(new Error('Unexpected failure'))
  })

  it('recognizes failed dynamic imports', () => {
    expect(
      getRouteErrorPresentation(
        new TypeError('Failed to fetch dynamically imported module: /assets/editor.js')
      )
    ).toMatchObject({
      title: '页面资源需要更新'
    })
  })

  it('renders recovery actions and development details', () => {
    render(<RouteErrorBoundary />)

    expect(screen.getByRole('heading', { name: '页面暂时无法显示' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新加载' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回首页' })).toHaveAttribute('href', '/home')
    expect(screen.getByText('错误详情')).toBeInTheDocument()
  })
})

it('refreshes recovery guidance and hides empty details when the routing error changes', () => {
  useRouteErrorMock.mockReturnValue(new Error('Failed to fetch dynamically imported module'))
  const view = render(<RouteErrorBoundary />)
  expect(screen.getByRole('heading', { name: '页面资源需要更新' })).toBeInTheDocument()
  useRouteErrorMock.mockReturnValue({
    status: 403,
    statusText: 'Forbidden',
    internal: false,
    data: null
  })
  view.rerender(<RouteErrorBoundary />)
  expect(screen.getByRole('heading', { name: '没有访问权限' })).toBeInTheDocument()
  expect(screen.getByText('403 Forbidden')).toBeInTheDocument()
  useRouteErrorMock.mockReturnValue('')
  view.rerender(<RouteErrorBoundary />)
  expect(screen.getByRole('heading', { name: '页面暂时无法显示' })).toBeInTheDocument()
  expect(screen.queryByText('错误详情')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '返回首页' })).toHaveAttribute('href', '/home')
})
