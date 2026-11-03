import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button, Form } from 'antd'
import type { ComponentProps } from 'react'
import { expect, it, vi } from 'vitest'

import type CodeMirrorEditor from '@/components/CodeMirrorEditor'
import { createEnvironment } from '@/test/test-utils'

import type { EnvironmentScriptFormValues } from '../model'
import ScriptForm from './ScriptForm'

// 仅替换编辑器渲染边界，表单字段注册、动态规则和提交校验使用真实 Ant Design。
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ value, onChange, readOnly, ariaLabel }: ComponentProps<typeof CodeMirrorEditor>) => (
    <textarea
      aria-label={ariaLabel}
      value={value ?? ''}
      readOnly={readOnly}
      onChange={(event) => onChange?.(event.target.value)}
    />
  )
}))
function show(values: Partial<EnvironmentScriptFormValues> = {}, busy = false) {
  const submit = vi.fn()
  render(
    <Form<EnvironmentScriptFormValues>
      disabled={busy}
      initialValues={{
        scriptType: 'CUSTOM',
        workdir: '/workspace',
        timeout: 60,
        content: 'pwd',
        ...values
      }}
      onFinish={submit}
    >
      <ScriptForm environment={createEnvironment({ name: 'riscv-target' })} busy={busy} />
      <Button htmlType="submit">执行脚本</Button>
    </Form>
  )
  return submit
}

it('rejects a whitespace custom script and submits the edited script with its execution context', async () => {
  const submit = show({ content: '  \n  ' })
  const actor = userEvent.setup()
  await actor.click(screen.getByRole('button', { name: '执行脚本' }))
  expect(await screen.findByText('请输入脚本内容')).toBeInTheDocument()
  expect(submit).not.toHaveBeenCalled()
  const content = screen.getByRole('textbox', { name: '脚本内容' })
  await actor.clear(content)
  await actor.type(content, 'uname -m')
  await actor.click(screen.getByRole('button', { name: '执行脚本' }))
  await waitFor(() =>
    expect(submit).toHaveBeenCalledExactlyOnceWith({
      scriptType: 'CUSTOM',
      workdir: '/workspace',
      timeout: 60,
      content: 'uname -m'
    })
  )
  expect(screen.getByDisplayValue('riscv-target')).toHaveValue('riscv-target')
  expect(screen.getByDisplayValue('riscv-target')).toBeDisabled()
})
it('requires a working directory before execution and permits correcting it', async () => {
  const submit = show({ workdir: '' })
  const actor = userEvent.setup()
  await actor.click(screen.getByRole('button', { name: '执行脚本' }))
  expect(await screen.findByText('请输入工作目录')).toBeInTheDocument()
  expect(submit).not.toHaveBeenCalled()
  await actor.type(screen.getByLabelText('工作目录'), '/build')
  await actor.click(screen.getByRole('button', { name: '执行脚本' }))
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ workdir: '/build' }))
  )
})
it.each([
  [0, 1],
  [86401, 86400]
])('rejects an invalid restored timeout %s and accepts boundary %s', async (invalid, valid) => {
  const submit = show({ timeout: invalid })
  const actor = userEvent.setup()
  // 恢复的配置也必须经表单校验，不能只依赖数字输入框的交互限制。
  fireEvent.submit(screen.getByRole('button', { name: '执行脚本' }).closest('form')!)
  expect(await screen.findByText('超时时间需介于 1 至 86400 秒')).toBeInTheDocument()
  expect(submit).not.toHaveBeenCalled()
  const timeout = screen.getByRole('spinbutton', { name: '超时时间' })
  await actor.clear(timeout)
  await actor.type(timeout, String(valid))
  await actor.click(screen.getByRole('button', { name: '执行脚本' }))
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ timeout: valid }))
  )
})
it('runs a built-in collection without requiring or displaying custom script content', async () => {
  const submit = show({ scriptType: 'COLLECTION', content: undefined })
  expect(screen.queryByRole('textbox', { name: '脚本内容' })).not.toBeInTheDocument()
  await userEvent.setup().click(screen.getByRole('button', { name: '执行脚本' }))
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({ scriptType: 'COLLECTION', workdir: '/workspace', timeout: 60 })
    )
  )
})
it('makes the custom script read-only while execution is busy', () => {
  show({}, true)
  expect(screen.getByRole('textbox', { name: '脚本内容' })).toHaveAttribute('readonly')
  expect(screen.getByLabelText('工作目录')).toBeDisabled()
  expect(screen.getByRole('button', { name: '执行脚本' })).toBeDisabled()
})
