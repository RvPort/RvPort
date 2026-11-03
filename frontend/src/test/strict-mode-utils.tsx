import { StrictMode } from 'react'
import type { ReactElement } from 'react'

import { renderWithQueryClient } from './test-utils'

// 与 main.tsx 的生命周期一致；重渲染也保留同一个 StrictMode 边界。
export function renderWithStrictQueryClient(
  ui: ReactElement,
  queryClient?: Parameters<typeof renderWithQueryClient>[1]
) {
  const view = renderWithQueryClient(<StrictMode>{ui}</StrictMode>, queryClient)
  return {
    ...view,
    rerender: (next: ReactElement) => view.rerender(<StrictMode>{next}</StrictMode>)
  }
}
