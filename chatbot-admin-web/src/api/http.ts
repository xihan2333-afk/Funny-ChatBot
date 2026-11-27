import {ElMessage} from 'element-plus'

export interface PageResult<T> {
    items: T[]
    total: number
    page: number
    pageSize: number
}

export class ApiError extends Error {
    status: number
    errorCode: string
    payload?: Record<string, unknown>

    constructor(
        status: number,
        errorCode: string,
        message: string,
        payload?: Record<string, unknown>,
    ) {
        super(message)
        this.name = 'ApiError'
        this.status = status
        this.errorCode = errorCode
        this.payload = payload
    }
}

const TOKEN_KEY = 'chatbot_admin_token'

export function getStoredToken(): string {
    return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setStoredToken(token: string): void {
    if (token) {
        localStorage.setItem(TOKEN_KEY, token)
    } else {
        localStorage.removeItem(TOKEN_KEY)
    }
}

export interface HttpHandlers {
    onUnauthorized?: () => void
    onMustChangePassword?: () => void
    notifyError?: (msg: string) => void
}

let handlers: HttpHandlers = {
    notifyError: (msg: string) => {
        ElMessage.error(msg)
    },
}

export function registerHttpHandlers(customHandlers: HttpHandlers): void {
    handlers = {...handlers, ...customHandlers}
}

export interface RequestOptions extends RequestInit {
    silent?: boolean
}

function buildHeaders(options?: RequestOptions): Headers {
    const headers = new Headers(options?.headers)
    const token = getStoredToken()
    if (token && !headers.has('Authorization')) {
        headers.set('Authorization', `Bearer ${token}`)
    }
    if (
        options?.body &&
        !(options.body instanceof FormData) &&
        !headers.has('Content-Type')
    ) {
        headers.set('Content-Type', 'application/json')
    }
    return headers
}

async function parseErrorResponse(res: Response): Promise<{
    errorCode: string
    message: string
    payload?: Record<string, unknown>
}> {
    const text = await res.text()
    if (!text) {
        return {errorCode: `http.${res.status}`, message: res.statusText || '请求失败'}
    }
    try {
        const json = JSON.parse(text) as Record<string, unknown>
        return {
            errorCode: typeof json.errorCode === 'string' ? json.errorCode : `http.${res.status}`,
            message: typeof json.message === 'string' ? json.message : '请求失败',
            payload:
                json.payload && typeof json.payload === 'object'
                    ? (json.payload as Record<string, unknown>)
                    : undefined,
        }
    } catch {
        return {errorCode: `http.${res.status}`, message: text}
    }
}

async function rawFetch(url: string, options?: RequestOptions): Promise<Response> {
    const res = await fetch(url, {
        ...options,
        headers: buildHeaders(options),
    })

    if (!res.ok) {
        const err = await parseErrorResponse(res)
        if (res.status === 401) {
            setStoredToken('')
            handlers.onUnauthorized?.()
            if (!options?.silent && url.includes('/auth/login')) {
                handlers.notifyError?.(err.message)
            }
        } else if (res.status === 403 && err.errorCode === 'security.mustChangePassword') {
            handlers.onMustChangePassword?.()
        } else if (!options?.silent) {
            if (res.status === 503) {
                handlers.notifyError?.('后端服务或观测存储暂不可用，请稍后重试')
            } else {
                handlers.notifyError?.(err.message)
            }
        }
        throw new ApiError(res.status, err.errorCode, err.message, err.payload)
    }

    return res
}

export async function request<T>(url: string, options?: RequestOptions): Promise<T> {
    const res = await rawFetch(url, options)
    if (res.status === 204) {
        return undefined as T
    }
    const text = await res.text()
    if (!text) {
        return undefined as T
    }
    return JSON.parse(text) as T
}

/**
 * 将 005 存量列表接口（裸数组 + X-Total-Count 或 LogPage）透明归一化为 PageResult<T>
 */
export async function adaptLegacyPage<T>(
    url: string,
    page = 1,
    pageSize = 20,
    options?: RequestOptions,
): Promise<PageResult<T>> {
    const res = await rawFetch(url, options)
    const totalHeader = res.headers.get('X-Total-Count')
    const text = await res.text()
    if (!text) {
        return {items: [], total: 0, page, pageSize}
    }
    const data = JSON.parse(text) as unknown
    if (Array.isArray(data)) {
        const total = totalHeader ? Number(totalHeader) : data.length
        return {items: data as T[], total: Number.isNaN(total) ? data.length : total, page, pageSize}
    }
    if (data && typeof data === 'object') {
        const obj = data as Record<string, unknown>
        if (Array.isArray(obj.items)) {
            return {
                items: obj.items as T[],
                total: typeof obj.total === 'number' ? obj.total : obj.items.length,
                page: typeof obj.page === 'number' ? obj.page : page,
                pageSize: typeof obj.pageSize === 'number' ? obj.pageSize : pageSize,
            }
        }
        if (Array.isArray(obj.entries)) {
            return {
                items: obj.entries as T[],
                total: typeof obj.total === 'number' ? obj.total : obj.entries.length,
                page,
                pageSize,
            }
        }
    }
    return {items: [], total: 0, page, pageSize}
}

export function buildQuery(params: Record<string, unknown>): string {
    const sp = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null && v !== '') {
            sp.set(k, String(v))
        }
    }
    const qs = sp.toString()
    return qs ? `?${qs}` : ''
}

export const http = {
    get: <T>(url: string, options?: RequestOptions) =>
        request<T>(url, {...options, method: 'GET'}),
    post: <T>(url: string, body?: unknown, options?: RequestOptions) =>
        request<T>(url, {
            ...options,
            method: 'POST',
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }),
    put: <T>(url: string, body?: unknown, options?: RequestOptions) =>
        request<T>(url, {
            ...options,
            method: 'PUT',
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }),
    patch: <T>(url: string, body?: unknown, options?: RequestOptions) =>
        request<T>(url, {
            ...options,
            method: 'PATCH',
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }),
    delete: <T>(url: string, body?: unknown, options?: RequestOptions) =>
        request<T>(url, {
            ...options,
            method: 'DELETE',
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }),
}
