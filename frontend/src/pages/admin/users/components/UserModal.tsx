import { Fragment, useEffect, useMemo } from 'react'
import { Button, Form, Input, Radio } from 'antd'

import BaseModal from '@/components/BaseModal'
import type { User } from '@/types'

interface UserFormValues {
  displayName?: string
  email: string
  status: User['status']
  username: string
}

interface UserModalProps {
  loading: boolean
  user: User | null
  visible: boolean
  onCancel: () => void
  onSubmit: (values: UserFormValues) => Promise<void>
}

const formId = 'admin-user-form'

export default function UserModal({ loading, user, visible, onCancel, onSubmit }: UserModalProps) {
  const [form] = Form.useForm<UserFormValues>()
  const isEditing = Boolean(user)

  const initialValues = useMemo(
    () => ({
      displayName: user?.displayName ?? undefined,
      email: user?.email ?? '',
      status: user?.status ?? 'ACTIVE',
      username: user?.username ?? ''
    }),
    [user]
  )

  useEffect(() => {
    if (visible) form.setFieldsValue(initialValues)
  }, [form, initialValues, visible])

  return (
    <BaseModal
      busy={loading}
      destroyOnHidden
      footer={
        <Fragment>
          <Button disabled={loading} onClick={onCancel}>
            取消
          </Button>
          <Button
            form={formId}
            htmlType="submit"
            disabled={loading}
            loading={loading}
            type="primary"
          >
            保存
          </Button>
        </Fragment>
      }
      title={{ title: isEditing ? '编辑用户' : '新增用户' }}
      visible={visible}
      onCancel={onCancel}
    >
      <Form<UserFormValues>
        disabled={loading}
        form={form}
        id={formId}
        initialValues={initialValues}
        layout="vertical"
        preserve={false}
        onFinish={(values) => {
          if (!loading) return onSubmit(values)
        }}
      >
        <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
          <Input
            autoComplete="username"
            disabled={loading || isEditing}
            placeholder="请输入账号"
            spellCheck={false}
          />
        </Form.Item>
        <Form.Item label="昵称" name="displayName">
          <Input placeholder="请输入昵称" />
        </Form.Item>
        <Form.Item
          label="邮箱"
          name="email"
          rules={[
            { required: true, message: '请输入邮箱' },
            { type: 'email', message: '请输入有效的邮箱地址' }
          ]}
        >
          <Input
            autoComplete="email"
            placeholder="name@example.com"
            spellCheck={false}
            type="email"
          />
        </Form.Item>
        <Form.Item label="状态" name="status">
          <Radio.Group
            options={[
              { label: '启用', value: 'ACTIVE' },
              { label: '禁用', value: 'DISABLED' }
            ]}
          />
        </Form.Item>
      </Form>
    </BaseModal>
  )
}
