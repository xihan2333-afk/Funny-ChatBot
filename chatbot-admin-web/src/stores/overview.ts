import {defineStore} from 'pinia'
import {ref} from 'vue'
import {fetchOverview, type OverviewResponse} from '@/api/overview'
import {useUiStore} from './ui'

export const useOverviewStore = defineStore('overview', () => {
    const data = ref<OverviewResponse | null>(null)
    const loading = ref(false)

    async function load() {
        loading.value = true
        try {
            const res = await fetchOverview()
            data.value = res
            const uiStore = useUiStore()
            uiStore.setTodoCounts(
                res.dailySnapshot.todo.unreadAlerts,
                res.dailySnapshot.todo.pendingBlindReviews,
                res.dailySnapshot.todo.pendingPersonaProposals + res.dailySnapshot.todo.pendingImprovementProposals,
            )
        } finally {
            loading.value = false
        }
    }

    return {
        data,
        loading,
        load,
    }
})
