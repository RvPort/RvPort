import { useState } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it } from 'vitest'

import { useSensitiveQueryToken } from './useSensitiveQueryToken'

function Harness() {
  const [parameter, setParameter] = useState<string>()
  const token = useSensitiveQueryToken(parameter)
  const location = useLocation(),
    navigate = useNavigate()
  return (
    <div>
      <output data-testid="token">{token ?? 'none'}</output>
      <output data-testid="location">
        {location.pathname}
        {location.search}
      </output>
      <button onClick={() => setParameter('invite')}>切换参数</button>
      <button onClick={() => navigate('/reset?invite=second&keep=1')}>新的邀请</button>
      <button onClick={() => navigate('/reset?invite=third&keep=1')}>更新邀请</button>
      <button onClick={() => navigate(-1)}>返回</button>
    </div>
  )
}
it('retains the captured token after sanitization, preserves unrelated parameters and replaces sensitive history', async () => {
  render(
    <MemoryRouter initialEntries={['/before', '/reset?token=first&keep=1']}>
      <Harness />
    </MemoryRouter>
  )
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reset?keep=1'))
  expect(screen.getByTestId('token')).toHaveTextContent('first')
  fireEvent.click(screen.getByText('返回'))
  expect(screen.getByTestId('location')).toHaveTextContent('/before')
})
it('does not reuse a token for a different parameter and captures replacement values of the same parameter', async () => {
  render(
    <MemoryRouter initialEntries={['/reset?token=first&keep=1']}>
      <Harness />
    </MemoryRouter>
  )
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reset?keep=1'))
  fireEvent.click(screen.getByText('切换参数'))
  expect(screen.getByTestId('token')).toHaveTextContent('none')
  fireEvent.click(screen.getByText('新的邀请'))
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reset?keep=1'))
  expect(screen.getByTestId('token')).toHaveTextContent('second')
  fireEvent.click(screen.getByText('更新邀请'))
  await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('third'))
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reset?keep=1'))
})
