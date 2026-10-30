import { StrictMode } from 'react'
import type { ReactElement } from 'react'

import { renderWithProjectOverview } from './project-overview-utils'

// 阶段组件同时使用真实概览上下文和应用入口的 StrictMode 生命周期。
export function renderWithStrictProjectOverview(
  ui: ReactElement,
  queryClient?: Parameters<typeof renderWithProjectOverview>[1]
) {
  const view = renderWithProjectOverview(<StrictMode>{ui}</StrictMode>, queryClient)
  return {
    ...view,
    rerender: (next: ReactElement) => view.rerender(<StrictMode>{next}</StrictMode>)
  }
}
