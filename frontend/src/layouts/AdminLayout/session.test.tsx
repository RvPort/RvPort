import { useState } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'

import AdminLayout from '.'

function Draft() {
  const [draft, setDraft] = useState('')
  return (
    <input
      aria-label="未提交内容"
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
    />
  )
}
it('clears the routed draft on authentication changes and supports all admin destinations', () => {
  render(
    <MemoryRouter initialEntries={['/admin/users']}>
      <Routes>
        <Route element={<AdminLayout />}>
          <Route path="/admin/users" element={<Draft />} />
          <Route path="/admin/mail" element={<h2>邮箱路由</h2>} />
          <Route path="/admin/models" element={<h2>模型路由</h2>} />
          <Route path="/admin/workers" element={<h2>Worker 路由</h2>} />
        </Route>
        <Route path="/home" element={<h2>项目首页</h2>} />
      </Routes>
    </MemoryRouter>
  )
  fireEvent.change(screen.getByRole('textbox', { name: '未提交内容' }), {
    target: { value: 'previous session draft' }
  })
  act(() => advanceAuthSession())
  expect(screen.getByRole('textbox', { name: '未提交内容' })).toHaveValue('')
  for (const [tab, heading] of [
    ['邮箱配置', '邮箱路由'],
    ['模型配置', '模型路由'],
    ['Worker 管理', 'Worker 路由']
  ]) {
    fireEvent.click(screen.getByRole('tab', { name: new RegExp(tab) }))
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
  }
  fireEvent.click(screen.getByRole('button', { name: '返回首页' }))
  expect(screen.getByRole('heading', { name: '项目首页' })).toBeInTheDocument()
})
