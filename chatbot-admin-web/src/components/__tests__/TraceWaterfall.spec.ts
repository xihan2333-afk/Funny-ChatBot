import {describe, expect, it} from 'vitest'
import {mount} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TraceWaterfall from '@/components/TraceWaterfall.vue'

describe('TraceWaterfall', () => {
    it('renders span tree and highlights chatbot.security.* spans', async () => {
        const wrapper = mount(TraceWaterfall, {
            global: {plugins: [ElementPlus]},
            props: {
                spans: [
                    {
                        spanId: 'sp-1',
                        parentSpanId: null,
                        name: 'turn.execute',
                        startTimeMs: 1000,
                        durationMs: 120,
                        status: 'OK',
                    },
                    {
                        spanId: 'sp-2',
                        parentSpanId: 'sp-1',
                        name: 'outbound.screen',
                        startTimeMs: 1020,
                        durationMs: 30,
                        status: 'OK',
                        attributes: {'chatbot.security.verdict': 'BLOCKED'},
                    },
                ],
            },
        })

        const rows = wrapper.findAll('.span-row')
        expect(rows.length).toBe(2)
        expect(rows[1]!.classes()).toContain('is-security')
        expect(wrapper.text()).toContain('安全事件')

        await rows[1]!.trigger('click')
        expect(wrapper.text()).toContain('outbound.screen')
    })
})
