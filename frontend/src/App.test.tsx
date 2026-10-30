import { StrictMode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'

import { queryClient } from '@/queryClient'
import { appNotification } from '@/utils/appNotification'
import { feedback } from '@/utils/feedback'

const load = vi.hoisted(() => vi.fn(async () => '项目数据已加载'))

function StartupProbe() {
  const query = useQuery({ queryKey: ['startup-probe'], queryFn: load })
  return (
    <>
      <p>{query.data}</p>
      <button
        onClick={() => {
          feedback.success('配置已保存')
          appNotification.warning({ title: '任务需要注意', description: '保留当前项目' })
        }}
      >
        发送业务反馈
      </button>
    </>
  )
}

vi.mock('@/router', async () => {
  const { createMemoryRouter } = await import('react-router')
  return { default: createMemoryRouter([{ path: '/', element: <StartupProbe /> }]) }
})

import App from './App'

it('provides query state and live message/notification bridges to routed content under the production StrictMode lifecycle', async () => {
  const view = render(
    <StrictMode>
      <App />
    </StrictMode>
  )
  try {
    expect(await screen.findByText('项目数据已加载')).toBeInTheDocument()
    expect(load).toHaveBeenCalledOnce()
    await userEvent.click(screen.getByRole('button', { name: '发送业务反馈' }))
    expect(await screen.findByText('配置已保存')).toBeInTheDocument()
    expect(await screen.findByText('任务需要注意')).toBeInTheDocument()
    expect(screen.getByText('保留当前项目')).toBeInTheDocument()
    view.unmount()
    act(() => {
      feedback.success('不应泄漏的旧反馈')
      appNotification.error({ title: '不应泄漏的旧通知' })
    })
    expect(screen.queryByText('不应泄漏的旧反馈')).not.toBeInTheDocument()
    expect(screen.queryByText('不应泄漏的旧通知')).not.toBeInTheDocument()
  } finally {
    view.unmount()
    queryClient.clear()
  }
})
