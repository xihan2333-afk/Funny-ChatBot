import {createApp} from 'vue'
import {createPinia} from 'pinia'
import ElementPlus, {ElMessage} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import {registerHttpHandlers} from './api/http'
import {useAuthStore} from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, {locale: zhCn})

const authStore = useAuthStore(pinia)
registerHttpHandlers({
    onUnauthorized: () => {
        authStore.clearSession()
        const current = router.currentRoute.value
        if (current.path !== '/login') {
            router.push({path: '/login', query: {redirect: current.fullPath}})
        }
    },
    onMustChangePassword: () => {
        authStore.mustChange = true
        if (router.currentRoute.value.path !== '/auth/change-password') {
            router.push('/auth/change-password')
        }
    },
    notifyError: (msg: string) => {
        ElMessage.error(msg)
    },
})

app.mount('#app')
