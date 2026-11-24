// 守护纪律：beforeToolCall 安全闸按副作用类别查矩阵（设计 :687/:843-847）——
// 群级禁用拦截、runCode 每群每日频控（跨日重置）、群副作用内容过滤只看字符串值；
// 无名调用不归闸管。
// 打桩纪律：不用 setup() 统一打桩（会压过各 feature 的重打桩），逐 feature 在 given 声明
package cn.edu.scut.chatbot.tool

import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.loop.AgentTool
import cn.edu.scut.chatbot.loop.RunConfig
import cn.edu.scut.chatbot.loop.ToolDescriptor
import cn.edu.scut.chatbot.loop.ToolInvocation
import cn.edu.scut.chatbot.tool.registry.ToolRegistry
import spock.lang.Specification

class ToolCallGuardTest extends Specification {

    def groupConfigMapper = Mock(GroupConfigMapper)
    def registry = Mock(ToolRegistry)
    def systemParams = Mock(SystemParamService)
    FixedClock clock = new FixedClock()

    ToolCallGuard guard = new ToolCallGuard(groupConfigMapper, registry, systemParams, clock)

    private AgentTool tool(String name, ToolDescriptor.EffectKind effectKind) {
        return new StubTool(name) {
            @Override
            ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "测试工具", "{\"type\":\"object\"}",
                        ToolDescriptor.ToolSpeed.FAST, effectKind, ToolDescriptor.ToolSource.BUILTIN, false)
            }
        }
    }

    private ToolInvocation invocation(String toolName, String argumentsJson = "{}") {
        return new ToolInvocation(argumentsJson, "qq", "10000", "80001",
                RunConfig.ExecutionForm.TURN, 1L, null, toolName)
    }

    def "群级禁用清单内的工具被拦截 拦截结果以错误形态直接入转录"() {
        given:
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        def config = new GroupConfigDO()
        config.setDisabledTools('["runCode"]')
        groupConfigMapper.selectByGroup(*_) >> config

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then:
        interception != null
        interception.overrideResult().isError()
        interception.overrideResult().content()[0].text().contains("runCode")
    }

    def "群级禁用清单外的同形态工具不受影响（闸按清单说话不按类别一刀切）"() {
        given:
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getInt("sandbox.daily_limit_per_group", 20) >> 20

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then:
        interception == null
    }

    def "runCode每群每日频控 触限后拦截且他群不受牵连"() {
        given: "运行层参数把每群每日上限调到 2（热变更即改策略）"
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getInt("sandbox.daily_limit_per_group", 20) >> 2

        when: "本群前两次调用"
        def first = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))
        def second = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then: "额度内放行"
        first == null
        second == null

        when: "第三次（超过 2 次上限）"
        def third = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then: "触限拦截——错误形态给模型、说明明日再试"
        third != null
        third.overrideResult().isError()
        third.overrideResult().content()[0].text().contains("2")

        and: "频控按群隔离：另一群同工具照常放行"
        guard.forRun("qq", "10000", "80002").intercept(
                new ToolInvocation("{}", "qq", "10000", "80002",
                        RunConfig.ExecutionForm.TURN, 1L, null, "runCode")) == null
    }

    def "频控计数跨日重置（注入时钟拨过午夜即新额度）"() {
        given: "起始时刻午夜前一分钟，上限 1"
        clock = new FixedClock(java.time.ZonedDateTime.of(2026, 5, 1, 23, 59, 0, 0, FixedClock.ZONE)
                .toInstant().toEpochMilli())
        guard = new ToolCallGuard(groupConfigMapper, registry, systemParams, clock)
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getInt("sandbox.daily_limit_per_group", 20) >> 1

        when: "今天第 1 次放行、第 2 次触限"
        def firstToday = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))
        def secondToday = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then:
        firstToday == null
        secondToday != null

        when: "拨过午夜（次日零点后）再调"
        clock.advanceMillis(2 * 60 * 1000L)
        def nextDay = guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))

        then: "新的一天额度重置——照常放行（『明天再试』名实相符）"
        nextDay == null
    }

    def "频控上限调为零以下即显式关闭（不限次）"() {
        given:
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getInt("sandbox.daily_limit_per_group", 20) >> 0

        when: "连打 30 次"
        def results = (1..30).collect {
            guard.forRun("qq", "10000", "80001").intercept(invocation("runCode"))
        }

        then:
        results.every { it == null }
    }

    def "本地执行类别的工具走频控（按effectKind分派 非仅按名字）"() {
        given:
        registry.snapshot() >> ["localShell": tool("localShell", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getInt("sandbox.daily_limit_per_group", 20) >> 1

        when:
        def first = guard.forRun("qq", "10000", "80001").intercept(invocation("localShell"))
        def second = guard.forRun("qq", "10000", "80001").intercept(invocation("localShell"))

        then: "名字不在 runCode 白名单但类别是本地执行——第二次触限"
        first == null
        second != null
        second.overrideResult().isError()
    }

    def "群副作用类工具内容过滤 字符串值命中即终止性拦截"() {
        given:
        registry.snapshot() >> ["sendMessage": tool("sendMessage", ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getString("safety.content_filter_words", "") >> " 炸弹 , 违禁词 "

        when: "发言文本（参数的字符串值，含嵌套值）含过滤词"
        def hit = guard.forRun("qq", "10000", "80001").intercept(
                invocation("sendMessage", '{"text":"我要发个炸弹看看","extra":{"nested":"夹在嵌套里"}}'))

        then: "拦截且要求收敛本次 Run（继续只会换措辞重试再次命中）"
        hit != null
        hit.overrideResult().isError()
        hit.overrideResult().terminate()
        hit.overrideResult().content()[0].text().contains("炸弹")
    }

    def "内容过滤只匹配字符串值 参数名命中或数字形态不拦截"() {
        given: "过滤词恰好是参数名 text 与数字串 123"
        registry.snapshot() >> ["sendMessage": tool("sendMessage", ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getString("safety.content_filter_words", "") >> "text,123"

        when: "参数名是 text、数值是 123——都不是字符串值"
        def interception = guard.forRun("qq", "10000", "80001").intercept(
                invocation("sendMessage", '{"text":"今天天气不错","count":123}'))

        then: "放行（对原始 JSON 文本直接 contains 会误报）"
        interception == null
    }

    def "内容过滤对纯查询矩阵行不生效（同类字样放行）"() {
        given:
        registry.snapshot() >> ["fetchWeb": tool("fetchWeb", ToolDescriptor.EffectKind.PURE_QUERY)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getString("safety.content_filter_words", "") >> "炸弹"

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(
                invocation("fetchWeb", '{"query":"炸弹的历史"}'))

        then:
        interception == null
    }

    def "词表为空默认放行（无策略即无拦截）"() {
        given:
        registry.snapshot() >> ["sendMessage": tool("sendMessage", ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getString("safety.content_filter_words", "") >> ""

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(
                invocation("sendMessage", '{"text":"随便说什么"}'))

        then:
        interception == null
    }

    def "参数JSON解析失败按无文本放行（坏参数归循环参数校验段管）"() {
        given:
        registry.snapshot() >> ["sendMessage": tool("sendMessage", ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT)]
        groupConfigMapper.selectByGroup(*_) >> null
        systemParams.getString("safety.content_filter_words", "") >> "炸弹"

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(
                invocation("sendMessage", '这不是合法JSON{{{'))

        then:
        interception == null
    }

    def "无名调用不归闸管辖（工具实现侧自组形态）"() {
        given: "即使群禁用清单与词表都就位"
        registry.snapshot() >> [:]
        def config = new GroupConfigDO()
        config.setDisabledTools('["sendMessage"]')
        groupConfigMapper.selectByGroup(*_) >> config
        systemParams.getString("safety.content_filter_words", "") >> "炸弹"
        def unnamed = new ToolInvocation("{\"text\":\"炸弹\"}", "qq", "10000", "80001",
                RunConfig.ExecutionForm.TURN, 1L, null)

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(unnamed)

        then: "放行（旧 7 参兼容构造形态），且不查注册表"
        interception == null
        0 * registry.snapshot()
    }

    def "不在注册表的工具放行（装配已过滤的口径，运行中新增交执行段报错）"() {
        given:
        registry.snapshot() >> [:]
        groupConfigMapper.selectByGroup(*_) >> null

        when:
        def interception = guard.forRun("qq", "10000", "80001").intercept(invocation("ghostTool"))

        then:
        interception == null
    }

    def "forRun绑定的群标识参与禁用判定（钩子与群绑定）"() {
        given:
        registry.snapshot() >> [(ToolCallGuard.RUN_CODE_TOOL): tool("runCode", ToolDescriptor.EffectKind.LOCAL_EXECUTION)]
        def config = new GroupConfigDO()
        config.setDisabledTools('["runCode"]')

        when: "绑定 90001 群的钩子"
        def bound = guard.forRun("qq", "20000", "90001").intercept(
                new ToolInvocation("{}", "qq", "20000", "90001",
                        RunConfig.ExecutionForm.TURN, 1L, null, "runCode"))

        then: "响应挂在 interaction 上（given 里同方法 stub 的响应会被 interaction 的空响应覆盖）"
        1 * groupConfigMapper.selectByGroup("qq", "20000", "90001") >> config
        bound != null
    }
}
