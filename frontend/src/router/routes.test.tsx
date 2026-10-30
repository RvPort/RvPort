import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthRequiredError, resetAuthStoreForTests, useAuthStore } from '@/store/authStore'
import type { User } from '@/types'

import routes from './routes'

// 保留真实路由树和守卫，只替换页面内容，避免权限测试发起页面业务请求。
vi.mock('@/layouts/AdminLayout', () => ({ default: () => <Outlet /> }))
vi.mock('@/layouts/MainLayout', () => ({ default: () => <Outlet /> }))
vi.mock('@/pages/admin/users', () => ({ default: () => <h1>用户管理页面</h1> }))
vi.mock('@/pages/admin/mail', () => ({ default: () => <h1>邮件管理页面</h1> }))
vi.mock('@/pages/admin/models', () => ({ default: () => <h1>模型管理页面</h1> }))
vi.mock('@/pages/admin/workers', () => ({ default: () => <h1>Worker 管理页面</h1> }))
vi.mock('@/pages/home', () => ({ default: () => <h1>项目首页</h1> }))
vi.mock('@/pages/settings/profile', () => ({ default: () => <h1>个人资料页面</h1> }))
vi.mock('@/pages/project-detail', () => ({ default: () => <h1>项目详情页面</h1> }))
vi.mock('@/pages/auth/login', () => ({ default: () => <h1>登录页面</h1> }))
vi.mock('@/pages/auth/forgot-password', () => ({ default: () => <h1>忘记密码页面</h1> }))
vi.mock('@/pages/auth/reset-password', () => ({ default: () => <h1>重置密码页面</h1> }))
vi.mock('@/pages/auth/accept-invitation', () => ({ default: () => <h1>接受邀请页面</h1> }))
vi.mock('@/pages/auth/force-change-password', () => ({ default: () => <h1>强制改密页面</h1> }))
vi.mock('@/pages/error/forbidden', () => ({ default: () => <h1>禁止访问页面</h1> }))
vi.mock('@/pages/error/not-found', () => ({ default: () => <h1>页面不存在</h1> }))

const adminPages = [
  ['/admin/users', '用户管理页面'],
  ['/admin/mail', '邮件管理页面'],
  ['/admin/models', '模型管理页面'],
  ['/admin/workers', 'Worker 管理页面']
] as const
const businessPages = ['/home', '/settings/profile', '/projects/project-1/workflow']
const protectedPaths = [...adminPages.map(([path]) => path), ...businessPages]
const guestPages = [
  ['/login', '登录页面'],
  ['/forgot-password', '忘记密码页面'],
  ['/reset-password?token=reset-token', '重置密码页面'],
  ['/accept-invitation?token=invitation-token', '接受邀请页面']
] as const
const routers: ReturnType<typeof createMemoryRouter>[] = []

function session(overrides: Partial<User> = {}) {
  const user: User = {
    id: 'user-1',
    username: 'developer',
    displayName: null,
    email: null,
    isSuperAdmin: false,
    mustChangePassword: false,
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: '2026-10-29T00:00:00Z',
    updatedAt: null,
    ...overrides
  }
  useAuthStore.setState({ ensureSession: vi.fn().mockResolvedValue(user), user })
}
async function visit(path: string, expectedPath: string, heading: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  routers.push(router)
  render(<RouterProvider router={router} />)
  await screen.findByRole('heading', { name: heading })
  await waitFor(() => {
    expect(router.state.navigation.state).toBe('idle')
    expect(router.state.errors).toBeNull()
    expect(router.state.location.pathname).toBe(expectedPath)
  })
  return router
}

beforeEach(() => {
  resetAuthStoreForTests()
  useAuthStore.setState({ ensureSession: vi.fn().mockRejectedValue(new AuthRequiredError()) })
})
afterEach(() => {
  for (const router of routers.splice(0)) router.dispose()
  resetAuthStoreForTests()
})

describe('route and guard bindings', () => {
  it.each(protectedPaths)('redirects an anonymous visit to %s to login', async (path) => {
    await visit(path, '/login', '登录页面')
  })
  it.each(adminPages)('denies a regular user direct access to %s', async (path) => {
    session()
    await visit(path, '/403', '禁止访问页面')
  })
  it.each(adminPages)('allows an administrator to open %s', async (path, heading) => {
    session({ isSuperAdmin: true })
    await visit(path, path, heading)
  })
  it.each(protectedPaths)('requires password change before allowing %s', async (path) => {
    session({ isSuperAdmin: true, mustChangePassword: true })
    await visit(path, '/force-change-password', '强制改密页面')
  })
  it.each([
    ['/home', '项目首页'],
    ['/settings/profile', '个人资料页面'],
    ['/projects/project-1/workflow', '项目详情页面']
  ])('allows a regular user to open %s', async (path, heading) => {
    session()
    await visit(path, path, heading)
  })
  it.each(guestPages)('allows an anonymous visitor to open %s', async (path, heading) => {
    const router = await visit(path, path.split('?')[0], heading)
    expect(router.state.location.search).toBe(path.includes('?') ? '?' + path.split('?')[1] : '')
  })
  it.each(guestPages)('redirects a signed-in user away from %s', async (path) => {
    session()
    await visit(path, '/home', '项目首页')
  })
  it('does not let an anonymous visitor open the required-password-change page', async () => {
    await visit('/force-change-password', '/login', '登录页面')
  })
  it('leaves the password-change page after the requirement is cleared', async () => {
    session()
    await visit('/force-change-password', '/home', '项目首页')
  })
  it('gives required password changes precedence over administrator permissions', async () => {
    session({ mustChangePassword: true })
    await visit('/admin/users', '/force-change-password', '强制改密页面')
  })
  it('redirects the authenticated root to the project home', async () => {
    session()
    await visit('/', '/home', '项目首页')
  })
  it('redirects an authorized admin root to user management', async () => {
    session({ isSuperAdmin: true })
    await visit('/admin', '/admin/users', '用户管理页面')
  })
  it('shows a public not-found page for an unmatched URL', async () => {
    await visit('/unknown-page', '/unknown-page', '页面不存在')
  })
})
