import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createPinia, setActivePinia} from 'pinia'
import {useAuthStore} from '@/stores/auth'
import {authNavigationGuard} from '@/router'
import {adaptLegacyPage, ApiError, http, registerHttpHandlers, setStoredToken} from '@/api/http'

describe('auth store & navigation guard', () => {
    beforeEach(() => {
        setActivePinia(createPinia())
        localStorage.clear()
    })

    it('redirects unauthenticated users to /login', () => {
        const next = vi.fn()
        authNavigationGuard(
            {path: '/overview', fullPath: '/overview', meta: {}} as any,
            {} as any,
            next,
        )
        expect(next).toHaveBeenCalledWith({path: '/login', query: {redirect: '/overview'}})
    })

    it('blocks mustChange users from accessing business routes and redirects to change-password', () => {
        const auth = useAuthStore()
        auth.token = 'tok-1'
        auth.mustChange = true

        const next = vi.fn()
        authNavigationGuard(
            {path: '/overview', fullPath: '/overview', meta: {}} as any,
            {} as any,
            next,
        )
        expect(next).toHaveBeenCalledWith('/auth/change-password')

        const nextAllowed = vi.fn()
        authNavigationGuard(
            {path: '/auth/change-password', fullPath: '/auth/change-password', meta: {allowMustChange: true}} as any,
            {} as any,
            nextAllowed,
        )
        expect(nextAllowed).toHaveBeenCalledWith()
    })

    it('allows normal authenticated users into business routes', () => {
        const auth = useAuthStore()
        auth.token = 'tok-2'
        auth.mustChange = false

        const next = vi.fn()
        authNavigationGuard(
            {path: '/overview', fullPath: '/overview', meta: {}} as any,
            {} as any,
            next,
        )
        expect(next).toHaveBeenCalledWith()
    })
})

describe('http client & 005 legacy pagination adapter', () => {
    const originalFetch = globalThis.fetch

    beforeEach(() => {
        localStorage.clear()
        setStoredToken('test-bearer')
    })

    afterEach(() => {
        globalThis.fetch = originalFetch
    })

    it('handles 401 unauthorized and 403 mustChangePassword', async () => {
        const onUnauthorized = vi.fn()
        const onMustChangePassword = vi.fn()
        registerHttpHandlers({onUnauthorized, onMustChangePassword, notifyError: vi.fn()})

        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(JSON.stringify({errorCode: 'security.unauthorized', message: 'expired'}), {
                status: 401,
            }),
        )
        await expect(http.get('/api/management/overview')).rejects.toBeInstanceOf(ApiError)
        expect(onUnauthorized).toHaveBeenCalledTimes(1)

        setStoredToken('test-bearer')
        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(JSON.stringify({errorCode: 'security.mustChangePassword', message: 'must change'}), {
                status: 403,
            }),
        )
        await expect(http.get('/api/management/overview')).rejects.toBeInstanceOf(ApiError)
        expect(onMustChangePassword).toHaveBeenCalledTimes(1)
    })

    it('shows friendly message on 503', async () => {
        const notifyError = vi.fn()
        registerHttpHandlers({notifyError})
        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(JSON.stringify({errorCode: 'observe.query_unavailable', message: 'ES down'}), {
                status: 503,
            }),
        )
        await expect(http.get('/api/management/observe/traces')).rejects.toBeInstanceOf(ApiError)
        expect(notifyError).toHaveBeenCalledWith('后端服务或观测存储暂不可用，请稍后重试')
    })

    it('adapts 005 bare array + X-Total-Count and LogPage into PageResult', async () => {
        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(JSON.stringify([{id: 1}, {id: 2}]), {
                status: 200,
                headers: {'X-Total-Count': '42'},
            }),
        )
        const res1 = await adaptLegacyPage<{ id: number }>('/api/management/eval/batches', 2, 10)
        expect(res1).toEqual({items: [{id: 1}, {id: 2}], total: 42, page: 2, pageSize: 10})

        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(JSON.stringify({entries: [{msg: 'hi'}], total: 9}), {status: 200}),
        )
        const res2 = await adaptLegacyPage<{ msg: string }>('/api/management/observe/logs', 1, 20)
        expect(res2).toEqual({items: [{msg: 'hi'}], total: 9, page: 1, pageSize: 20})
    })
})
