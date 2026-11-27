import {defineStore} from 'pinia'
import {computed, ref} from 'vue'
import {getStoredToken, http, setStoredToken} from '@/api/http'

export interface LoginResult {
    token: string
    username: string
    mustChange: boolean
}

export interface AuthStatus {
    username: string
    mustChange: boolean
}

export const useAuthStore = defineStore('auth', () => {
    const token = ref<string>(getStoredToken())
    const username = ref<string>(localStorage.getItem('chatbot_admin_username') ?? '')
    const mustChange = ref<boolean>(localStorage.getItem('chatbot_admin_must_change') === 'true')
    const statusChecked = ref<boolean>(false)

    const isAuthenticated = computed(() => Boolean(token.value))

    function applySession(newToken: string, newUsername: string, newMustChange: boolean) {
        token.value = newToken
        username.value = newUsername
        mustChange.value = newMustChange
        setStoredToken(newToken)
        if (newToken) {
            localStorage.setItem('chatbot_admin_username', newUsername)
            localStorage.setItem('chatbot_admin_must_change', String(newMustChange))
        } else {
            localStorage.removeItem('chatbot_admin_username')
            localStorage.removeItem('chatbot_admin_must_change')
        }
    }

    function clearSession() {
        applySession('', '', false)
        statusChecked.value = false
    }

    async function login(user: string, password: string): Promise<LoginResult> {
        const res = await http.post<LoginResult>('/api/management/auth/login', {
            username: user,
            password,
        })
        applySession(res.token, res.username, res.mustChange)
        statusChecked.value = true
        return res
    }

    async function logout(): Promise<void> {
        try {
            if (token.value) {
                await http.post<void>('/api/management/auth/logout', undefined, {silent: true})
            }
        } finally {
            clearSession()
        }
    }

    async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
        await http.post<{ changed: boolean; reloginRequired: boolean }>(
            '/api/management/auth/change-password',
            {oldPassword, newPassword},
        )
        clearSession()
    }

    async function fetchStatus(): Promise<AuthStatus | null> {
        if (!token.value) {
            return null
        }
        const status = await http.get<AuthStatus>('/api/management/auth/status', {silent: true})
        username.value = status.username
        mustChange.value = status.mustChange
        localStorage.setItem('chatbot_admin_username', status.username)
        localStorage.setItem('chatbot_admin_must_change', String(status.mustChange))
        statusChecked.value = true
        return status
    }

    return {
        token,
        username,
        mustChange,
        statusChecked,
        isAuthenticated,
        login,
        logout,
        changePassword,
        fetchStatus,
        clearSession,
    }
})
