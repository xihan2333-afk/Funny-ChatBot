import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {flushPromises, mount} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import VersionPublishDialog from '@/components/VersionPublishDialog.vue'

describe('VersionPublishDialog', () => {
    const originalFetch = globalThis.fetch

    beforeEach(() => {
        localStorage.setItem('chatbot_admin_token', 'tok-123')
    })

    afterEach(() => {
        globalThis.fetch = originalFetch
    })

    it('requires overrideReason when gate status is red', async () => {
        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(
                JSON.stringify({
                    intention: 'skill:demo',
                    status: 'red',
                    emptySubset: false,
                    totalCases: 2,
                    passedCases: 1,
                    verdicts: [],
                    redLinePassed: false,
                    experienceWarnings: [],
                    summary: '红线题未通过',
                }),
                {status: 200},
            ),
        )

        const executor = vi.fn().mockResolvedValue(undefined)
        const wrapper = mount(VersionPublishDialog, {
            global: {plugins: [ElementPlus]},
            props: {
                visible: false,
                entityKind: 'skill',
                entityKey: 'demo',
                publishExecutor: executor,
            },
        })

        await wrapper.setProps({visible: true})
        await flushPromises()

        const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('确认发布'))
        expect(publishBtn?.attributes('disabled')).toBeDefined()

        const textarea = wrapper.find('textarea')
        await textarea.setValue('紧急修复线上问题')
        await flushPromises()

        expect(publishBtn?.attributes('disabled')).toBeUndefined()
        await publishBtn?.trigger('click')
        await flushPromises()
        expect(executor).toHaveBeenCalledWith('紧急修复线上问题')
    })

    it('allows direct publish when gate status is green', async () => {
        globalThis.fetch = vi.fn().mockResolvedValueOnce(
            new Response(
                JSON.stringify({
                    intention: 'fragment:f1',
                    status: 'green',
                    emptySubset: false,
                    totalCases: 2,
                    passedCases: 2,
                    verdicts: [],
                    redLinePassed: true,
                    experienceWarnings: [],
                    summary: '门禁全部通过',
                }),
                {status: 200},
            ),
        )

        const executor = vi.fn().mockResolvedValue(undefined)
        const wrapper = mount(VersionPublishDialog, {
            global: {plugins: [ElementPlus]},
            props: {
                visible: false,
                entityKind: 'fragment',
                entityKey: 'f1',
                publishExecutor: executor,
            },
        })

        await wrapper.setProps({visible: true})
        await flushPromises()

        const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('确认发布'))
        expect(publishBtn?.attributes('disabled')).toBeUndefined()
        await publishBtn?.trigger('click')
        await flushPromises()
        expect(executor).toHaveBeenCalledWith(null)
    })
})
