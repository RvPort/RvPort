import type { RouteObject } from 'react-router'
import { redirect } from 'react-router'

import { requireAdmin, requireFullAccess, requireGuest, requirePasswordChange } from './guards'
import RouteErrorBoundary from './RouteErrorBoundary'
import RouteFallback from './RouteFallback'

const routes: RouteObject[] = [
  {
    ErrorBoundary: RouteErrorBoundary,
    HydrateFallback: RouteFallback,
    children: [
      {
        path: 'login',
        loader: requireGuest,
        lazy: async () => {
          const { default: Component } = await import('@/pages/auth/login')
          return { Component }
        }
      },
      {
        path: 'forgot-password',
        loader: requireGuest,
        lazy: async () => {
          const { default: Component } = await import('@/pages/auth/forgot-password')
          return { Component }
        }
      },
      {
        path: 'reset-password',
        loader: requireGuest,
        lazy: async () => {
          const { default: Component } = await import('@/pages/auth/reset-password')
          return { Component }
        }
      },
      {
        path: 'accept-invitation',
        loader: requireGuest,
        lazy: async () => {
          const { default: Component } = await import('@/pages/auth/accept-invitation')
          return { Component }
        }
      },
      {
        path: 'force-change-password',
        loader: requirePasswordChange,
        lazy: async () => {
          const { default: Component } = await import('@/pages/auth/force-change-password')
          return { Component }
        }
      },
      {
        path: '403',
        lazy: async () => {
          const { default: Component } = await import('@/pages/error/forbidden')
          return { Component }
        }
      },
      {
        path: '500',
        lazy: async () => {
          const { default: Component } = await import('@/pages/error/server')
          return { Component }
        }
      },
      {
        path: '404',
        lazy: async () => {
          const { default: Component } = await import('@/pages/error/not-found')
          return { Component }
        }
      },
      {
        path: 'admin',
        loader: requireAdmin,
        lazy: async () => ({ Component: (await import('@/layouts/AdminLayout')).default }),
        children: [
          { index: true, Component: RouteFallback, loader: () => redirect('/admin/users') },
          {
            path: 'users',
            lazy: async () => ({ Component: (await import('@/pages/admin/users')).default })
          },
          {
            path: 'mail',
            lazy: async () => ({ Component: (await import('@/pages/admin/mail')).default })
          },
          {
            path: 'models',
            lazy: async () => ({ Component: (await import('@/pages/admin/models')).default })
          },
          {
            path: 'workers',
            lazy: async () => ({ Component: (await import('@/pages/admin/workers')).default })
          },
          {
            path: 'skills',
            lazy: async () => ({ Component: (await import('@/pages/admin/skills')).default })
          }
        ]
      },
      {
        path: '/',
        loader: requireFullAccess,
        lazy: async () => ({ Component: (await import('@/layouts/MainLayout')).default }),
        children: [
          {
            index: true,
            Component: RouteFallback,
            loader: () => redirect('/home')
          },
          {
            path: 'home',
            lazy: async () => {
              const { default: Component } = await import('@/pages/home')
              return { Component }
            }
          },
          {
            path: 'settings/profile',
            lazy: async () => {
              const { default: Component } = await import('@/pages/settings/profile')
              return { Component }
            }
          },
          {
            path: 'projects/:projectId/*',
            lazy: async () => {
              const { default: Component } = await import('@/pages/project-detail')
              return { Component }
            }
          }
        ]
      },
      {
        path: '*',
        lazy: async () => {
          const { default: Component } = await import('@/pages/error/not-found')
          return { Component }
        }
      }
    ]
  }
]

export default routes
