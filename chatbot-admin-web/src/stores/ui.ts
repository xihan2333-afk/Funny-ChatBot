import {defineStore} from 'pinia'
import {ref} from 'vue'

export interface GroupTriple {
    platform: string
    botId: string
    groupId: string
}

export const useUiStore = defineStore('ui', () => {
    const sidebarCollapsed = ref(false)
    const todoUnreadAlerts = ref(0)
    const todoPendingBlind = ref(0)
    const todoPendingProposals = ref(0)
    const selectedGroup = ref<GroupTriple | null>(null)

    function toggleSidebar() {
        sidebarCollapsed.value = !sidebarCollapsed.value
    }

    function setTodoCounts(unreadAlerts: number, pendingBlind: number, pendingProposals: number) {
        todoUnreadAlerts.value = unreadAlerts
        todoPendingBlind.value = pendingBlind
        todoPendingProposals.value = pendingProposals
    }

    return {
        sidebarCollapsed,
        todoUnreadAlerts,
        todoPendingBlind,
        todoPendingProposals,
        selectedGroup,
        toggleSidebar,
        setTodoCounts,
    }
})
