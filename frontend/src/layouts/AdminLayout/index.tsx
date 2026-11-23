import { useSyncExternalStore } from 'react'
import { Fragment, Suspense } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import {
  ApiOutlined,
  ClusterOutlined,
  HomeOutlined,
  MailOutlined,
  TeamOutlined,
  ToolOutlined
} from '@ant-design/icons'
import { Button, Flex, Layout, Tabs, Tag } from 'antd'

import ContentState from '@/components/ContentState'
import { getAuthGeneration, subscribeAuthSession } from '@/store/authSession'

import styles from './index.module.scss'

const { Header, Content } = Layout

const items = [
  {
    key: '/admin/users',
    label: (
      <Fragment>
        <TeamOutlined /> 用户管理
      </Fragment>
    )
  },
  {
    key: '/admin/mail',
    label: (
      <Fragment>
        <MailOutlined /> 邮箱配置
      </Fragment>
    )
  },
  {
    key: '/admin/models',
    label: (
      <Fragment>
        <ApiOutlined /> 模型配置
      </Fragment>
    )
  },
  {
    key: '/admin/workers',
    label: (
      <Fragment>
        <ClusterOutlined /> Worker 管理
      </Fragment>
    )
  },
  {
    key: '/admin/skills',
    label: (
      <Fragment>
        <ToolOutlined /> AI 迁移技能
      </Fragment>
    )
  }
]

export default function AdminLayout() {
  const generation = useSyncExternalStore(
    subscribeAuthSession,
    getAuthGeneration,
    getAuthGeneration
  )
  const location = useLocation()
  const navigate = useNavigate()

  return (
    <Layout className={styles.layout}>
      <a className={styles.skipLink} href="#main-content">
        跳至主内容
      </a>
      <Header className={styles.header}>
        <Flex align="center" justify="space-between">
          <Flex align="center" gap={12}>
            <Button
              aria-label="返回首页"
              icon={<HomeOutlined />}
              type="text"
              onClick={() => void navigate('/home')}
            />
            <h1>管理中心</h1>
            <Tag color="processing">超级管理员</Tag>
          </Flex>
        </Flex>
      </Header>
      <Tabs
        activeKey={location.pathname}
        className={styles.tabs}
        items={items}
        onChange={(key) => void navigate(key)}
      />
      <Content className={styles.content} id="main-content" tabIndex={-1}>
        <div className={styles.contentInner}>
          <Suspense
            fallback={<ContentState kind="loading" description="正在加载管理页面" compact />}
          >
            <Outlet key={generation} />
          </Suspense>
        </div>
      </Content>
    </Layout>
  )
}
