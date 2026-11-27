import {describe, expect, it} from 'vitest'
import {flushPromises, mount} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'

describe('HighRiskConfirmDialog', () => {
    it('disables confirm button until keyword matches', async () => {
        const wrapper = mount(HighRiskConfirmDialog, {
            global: {plugins: [ElementPlus]},
            props: {
                visible: true,
                title: '高危确认',
                warningText: '不可逆操作',
                confirmKeyword: 'ERASE_USER',
            },
        })

        await flushPromises()
        const confirmBtn = wrapper.findAll('button').find((b) => b.text().includes('确认执行'))
        expect(confirmBtn?.attributes('disabled')).toBeDefined()

        const input = wrapper.find('input')
        await input.setValue('ERASE_USER')
        await flushPromises()

        expect(confirmBtn?.attributes('disabled')).toBeUndefined()
        await confirmBtn?.trigger('click')
        expect(wrapper.emitted('confirm')).toBeTruthy()
    })
})
