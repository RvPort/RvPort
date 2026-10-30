import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { AxiosError, AxiosHeaders, CanceledError } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { advanceAuthSession, SessionChangedError } from '@/store/authSession'
import { resetAuthStoreForTests, useAuthStore } from '@/store/authStore'
import { registerFeedbackMessage } from '@/utils/feedback'

import request, {
  DEFAULT_REQUEST_TIMEOUT_MS,
  requestClient,
  resetRequestClientForTests,
  UPLOAD_REQUEST_TIMEOUT_MS
} from './request'

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return {
    config,
    data,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers: new AxiosHeaders()
  }
}

describe('request client', () => {
  let defaultAdapter: typeof requestClient.defaults.adapter

  beforeEach(() => {
    defaultAdapter = requestClient.defaults.adapter
    resetRequestClientForTests()
    resetAuthStoreForTests()
  })

  afterEach(() => {
    requestClient.defaults.adapter = defaultAdapter
    registerFeedbackMessage(null)
    resetRequestClientForTests()
    resetAuthStoreForTests()
  })

  it('uses a bounded default timeout for ordinary requests', () => {
    expect(requestClient.defaults.timeout).toBe(DEFAULT_REQUEST_TIMEOUT_MS)
    expect(DEFAULT_REQUEST_TIMEOUT_MS).toBe(30_000)
    expect(UPLOAD_REQUEST_TIMEOUT_MS).toBe(300_000)
  })

  it('does not show a network error for an intentionally cancelled read', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    requestClient.defaults.adapter = async (config) => {
      throw new CanceledError('canceled', config)
    }
    await expect(request.get('/projects')).rejects.toBeInstanceOf(CanceledError)
    expect(error).not.toHaveBeenCalled()
  })

  it('unwraps successful responses and attaches one CSRF token to unsafe requests', async () => {
    const calls: InternalAxiosRequestConfig[] = []
    requestClient.defaults.adapter = async (config) => {
      calls.push(config)
      if (config.url === '/auth/csrf') {
        return response(config, {
          success: true,
          data: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-token' },
          traceId: 'trace-csrf',
          timestamp: '2026-07-13T00:00:00Z'
        })
      }

      return response(config, {
        success: true,
        data: { saved: true },
        traceId: 'trace-save',
        timestamp: '2026-07-13T00:00:00Z'
      })
    }

    await expect(request.post<{ saved: boolean }>('/projects', { name: 'demo' })).resolves.toEqual({
      saved: true
    })
    expect(calls).toHaveLength(2)
    expect(calls[0].url).toBe('/auth/csrf')
    expect(calls[1].headers.get('X-CSRF-TOKEN')).toBe('csrf-token')
  })

  it('clears authentication on an unauthorized response without redirect or toast when suppressed', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    useAuthStore.getState().setUser({
      id: 'user-1',
      username: 'admin',
      displayName: null,
      email: null,
      isSuperAdmin: true,
      mustChangePassword: false,
      status: 'ACTIVE',
      lastLoginAt: null,
      createdAt: '2026-07-13T00:00:00Z',
      updatedAt: null
    })
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Unauthorized',
          undefined,
          config,
          undefined,
          response(config, { error: { message: '未登录' } }, 401)
        )
      )

    await expect(
      request.get('/private', { suppressAuthRedirect: true, suppressErrorToast: true })
    ).rejects.toThrow('Unauthorized')
    expect(useAuthStore.getState().user).toBeNull()
    expect(error).not.toHaveBeenCalled()
  })

  it('shows the backend message for an unauthorized request by default', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Unauthorized',
          undefined,
          config,
          undefined,
          response(config, { code: 'AUTH_REQUIRED', message: '需要登录' }, 401)
        )
      )

    await expect(request.get('/private', { suppressAuthRedirect: true })).rejects.toThrow(
      'Unauthorized'
    )
    expect(error).toHaveBeenCalledWith('需要登录')
  })

  it('does not treat a generic forbidden response as a password-change requirement', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    window.history.replaceState({}, '', '/403')
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Forbidden',
          undefined,
          config,
          undefined,
          response(config, { error: { message: '没有权限' } }, 403)
        )
      )

    await expect(request.get('/private')).rejects.toThrow('Forbidden')
    expect(error).toHaveBeenCalledWith('没有权限')
  })

  it('does not treat password-change-required as a generic forbidden error', async () => {
    const error = vi.fn()
    const refreshUser = vi.fn()
    registerFeedbackMessage({ error } as never)
    useAuthStore.setState({ refreshUser })
    window.history.replaceState({}, '', '/force-change-password')
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Password change required',
          undefined,
          config,
          undefined,
          response(config, { code: 'PASSWORD_CHANGE_REQUIRED', message: '用户必须先修改密码' }, 403)
        )
      )

    await expect(request.get('/private')).rejects.toThrow('Password change required')
    expect(refreshUser).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
  })

  it('keeps gateway errors local and shows their message', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    window.history.replaceState({}, '', '/500')
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Bad Gateway',
          undefined,
          config,
          undefined,
          response(config, { message: 'Bad Gateway' }, 502)
        )
      )

    await expect(request.get('/projects/project-1/overview')).rejects.toThrow('Bad Gateway')
    expect(error).toHaveBeenCalledWith('Bad Gateway')
  })

  it('keeps a single-endpoint 500 error on the current page for its local error state', async () => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    window.history.replaceState({}, '', '/projects/project-1')
    requestClient.defaults.adapter = async (config) =>
      Promise.reject(
        new AxiosError(
          'Internal Server Error',
          undefined,
          config,
          undefined,
          response(config, { message: '项目概览加载失败' }, 500)
        )
      )

    await expect(request.get('/projects/project-1/overview')).rejects.toThrow(
      'Internal Server Error'
    )
    expect(window.location.pathname).toBe('/projects/project-1')
    expect(error).toHaveBeenCalledWith('项目概览加载失败')
  })
  it.each([200, 401, 500])(
    'ignores late old %s responses without auth cleanup, feedback or redirect',
    async (status) => {
      const errorToast = vi.fn()
      registerFeedbackMessage({ error: errorToast } as never)
      let finish!: () => void
      requestClient.defaults.adapter = (config) =>
        new Promise((yes, no) => {
          finish = () =>
            status === 200
              ? yes(response(config, { data: 'old' }))
              : no(
                  new AxiosError(
                    'old',
                    undefined,
                    config,
                    undefined,
                    response(config, { error: { message: 'old' } }, status)
                  )
                )
        })
      const pending = request.get('/old').catch((error: Error) => error)
      await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
      const location = window.location.href
      advanceAuthSession()
      const logout = vi.spyOn(useAuthStore.getState(), 'logout')
      finish()
      expect(await pending).toMatchObject({ name: 'SessionChangedError' })
      expect(logout).not.toHaveBeenCalled()
      expect(errorToast).not.toHaveBeenCalled()
      expect(window.location.href).toBe(location)
    }
  )

  it('isolates the CSRF promise and does not send an old queued write', async () => {
    let finishOld!: () => void, finishNew!: () => void
    const writes: string[] = []
    let csrfCalls = 0
    requestClient.defaults.adapter = (config) => {
      if (config.url === '/auth/csrf') {
        csrfCalls++
        return new Promise((yes) => {
          const finish = () =>
            yes(
              response(config, {
                data: {
                  headerName: 'X-CSRF-TOKEN',
                  parameterName: '_csrf',
                  token: csrfCalls === 1 ? 'old' : 'new'
                }
              })
            )
          if (csrfCalls === 1) finishOld = finish
          else finishNew = finish
        })
      }
      writes.push(config.headers.get('X-CSRF-TOKEN') as string)
      return Promise.resolve(response(config, { data: 'saved' }))
    }
    const old = request.post('/old').catch((error: Error) => error)
    await vi.waitFor(() => expect(csrfCalls).toBe(1))
    advanceAuthSession()
    const next = request.post('/new')
    await vi.waitFor(() => expect(csrfCalls).toBe(2))
    finishOld()
    expect(await old).toMatchObject({ name: 'SessionChangedError' })
    const duplicate = request.post('/new2')
    await Promise.resolve()
    expect(csrfCalls).toBe(2)
    finishNew()
    await Promise.all([next, duplicate])
    expect(writes).toEqual(['new', 'new'])
  })
  it.each([
    [401, '登录已失效，请重新登录'],
    [403, '没有权限执行此操作'],
    [502, '网络异常，请稍后重试']
  ])('uses a useful fallback for empty %s error messages', async (status, message) => {
    const error = vi.fn()
    registerFeedbackMessage({ error } as never)
    window.history.replaceState({}, '', '/login')
    requestClient.defaults.adapter = async (config) => {
      throw new AxiosError(
        '',
        undefined,
        config,
        undefined,
        response(config, { message: '' }, Number(status))
      )
    }
    await expect(request.get('/private')).rejects.toBeInstanceOf(AxiosError)
    expect(error).toHaveBeenCalledWith(message)
  })
  it.each([403, 500])(
    'suppresses optional error feedback for %s without clearing auth',
    async (status) => {
      const error = vi.fn()
      registerFeedbackMessage({ error } as never)
      const logout = vi.spyOn(useAuthStore.getState(), 'logout')
      requestClient.defaults.adapter = async (config) => {
        throw new AxiosError(
          'denied',
          undefined,
          config,
          undefined,
          response(config, { error: { code: 'DENIED', message: 'denied' } }, status)
        )
      }
      await expect(request.get('/optional', { suppressErrorToast: true })).rejects.toThrow('denied')
      expect(error).not.toHaveBeenCalled()
      expect(logout).not.toHaveBeenCalled()
    }
  )
  it('reuses a resolved CSRF token across write methods and permits explicitly exempt writes', async () => {
    const calls: InternalAxiosRequestConfig[] = []
    requestClient.defaults.adapter = async (config) => {
      calls.push(config)
      return response(config, {
        data:
          config.url === '/auth/csrf'
            ? { headerName: 'X-CSRF', parameterName: '_csrf', token: 'shared' }
            : 'saved'
      })
    }
    await request.put('/one', {})
    await request.patch('/two', {})
    await request.delete('/three')
    await request.post('/exempt', {}, { skipCsrf: true })
    expect(calls.filter((call) => call.url === '/auth/csrf')).toHaveLength(1)
    expect(
      calls
        .filter((call) => ['/one', '/two', '/three'].includes(call.url!))
        .map((call) => call.headers.get('X-CSRF'))
    ).toEqual(['shared', 'shared', 'shared'])
    expect(calls.find((call) => call.url === '/exempt')!.headers.has('X-CSRF')).toBe(false)
  })
  it('rejects a queued request before transport if authentication changes before its interceptor runs', async () => {
    const transport = vi.fn(async (config) => response(config, { data: 'should not be read' }))
    requestClient.defaults.adapter = transport
    const pending = request.get('/queued')
    const rejection = expect(pending).rejects.toBeInstanceOf(SessionChangedError)
    advanceAuthSession()
    await rejection
    expect(transport).not.toHaveBeenCalled()
  })
  it.each([
    [401, { error: { message: '需要登录' } }, '/login'],
    [
      403,
      { error: { code: 'PASSWORD_CHANGE_REQUIRED', message: '必须修改密码' } },
      '/force-change-password'
    ]
  ] as const)(
    'redirects an active %s response to the required session recovery route',
    async (status, payload, target) => {
      const location = { pathname: '/projects/project-1', href: '/projects/project-1' }
      vi.stubGlobal('window', { location })
      try {
        requestClient.defaults.adapter = async (config) => {
          throw new AxiosError(
            'denied',
            undefined,
            config,
            undefined,
            response(config, payload, status)
          )
        }
        await expect(request.get('/protected')).rejects.toBeInstanceOf(AxiosError)
        expect(location.href).toBe(target)
      } finally {
        vi.unstubAllGlobals()
      }
    }
  )
})
