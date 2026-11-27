package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * systemPrompt 四层组装器（spec US6、设计方案 2.2.5）：
 *
 * <ol>
 *   <li>身份层 = 内置锁定的骨架总则 + 人格片段（Worker 无身份层）</li>
 *   <li>群上下文层 = 群名/平台/成员概况/群规（Worker 无群上下文层）</li>
 *   <li>能力协议层 = 发言协议 + 工具总则 + 技能目录（Worker 无发言协议）</li>
 *   <li>任务层 = 轻任务指令 / Worker 任务执行提示词（Turn 无任务层）</li>
 * </ol>
 *
 * <p>纪律：同样输入组装出同样提示词（确定性断言）；当前时间不进 systemPrompt
 * （由现注区注入）；变量渲染朴素 {{变量路径}} 占位（不引模板引擎）、未定义渲染空并告警、
 * 不可信变量以 untrusted 标签定界注入（US7 XML 形态，单一事实源在 security/delimit）。
 */
@Component
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");

    private final PromptFragmentMapper fragmentMapper;
    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final PersonaResolver personaResolver;
    private final SkillDirectoryRenderer skillDirectoryRenderer;
    private final cn.edu.scut.chatbot.memory.profile.GroupProfileMapper groupProfileMapper;

    public PromptAssembler(PromptFragmentMapper fragmentMapper,
                           GroupMapper groupMapper,
                           GroupConfigMapper groupConfigMapper,
                           PersonaResolver personaResolver,
                           SkillDirectoryRenderer skillDirectoryRenderer,
                           cn.edu.scut.chatbot.memory.profile.GroupProfileMapper groupProfileMapper) {
        this.fragmentMapper = fragmentMapper;
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.personaResolver = personaResolver;
        this.skillDirectoryRenderer = skillDirectoryRenderer;
        this.groupProfileMapper = groupProfileMapper;
    }

    /**
     * 定界 + 注入检测组合件（US7 不可信变量通道——群上下文层）。
     * setter 注入：未装配（隔离单测上下文）时回退静态定界，只丢检测不丢定界。
     */
    private cn.edu.scut.chatbot.security.delimit.DelimitGuard delimitGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDelimitGuard(cn.edu.scut.chatbot.security.delimit.DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    /**
     * 按形态组装 systemPrompt（确定性：同样输入同样输出）
     *
     * @param form          执行形态（层序裁配依据）
     * @param taskLayerText 任务层文本（轻任务指令 / Worker 执行提示词；Turn 传 null）
     */
    public String assemble(RunConfig.ExecutionForm form,
                           String platform, String botId, String groupId, String taskLayerText) {
        return assembleDetailed(form, platform, botId, groupId, taskLayerText).prompt();
    }

    /**
     * 组装结果（观测版本三键的取数面）：promptFragment = 本次实际用到的 published
     * 片段最大版本号；persona = 绑定人格 version_no（无绑定或解析失败为 0）
     */
    public record AssembleOutcome(String prompt, long fragmentVersion, long personaVersion) {
    }

    /**
     * 按形态组装 systemPrompt 并带出版本信息（assemble 的详细形态——engine 在 RunConfig
     * 组装点消费版本三键，其余调用方仍用 assemble 拿纯文本）
     */
    public AssembleOutcome assembleDetailed(RunConfig.ExecutionForm form,
                                            String platform, String botId, String groupId,
                                            String taskLayerText) {
        List<String> layers = new ArrayList<>();
        boolean worker = form == RunConfig.ExecutionForm.WORKER;
        long fragmentVersion = 0;
        long personaVersion = 0;

        Map<String, String> variables = buildVariables(platform, botId, groupId);

        // 身份层（Worker 无）——人格消费：变体抽取代码执行、语义雷点素材进身份层
        if (!worker) {
            IdentityRender identity = renderIdentityLayer(form, variables, platform, botId, groupId);
            layers.add(identity.text());
            fragmentVersion = Math.max(fragmentVersion, identity.maxFragmentVersion());
            personaVersion = identity.personaVersion();
        }
        // 群上下文层（Worker 无——Worker 不读群聊）
        if (!worker && groupId != null) {
            layers.add(renderGroupContext(platform, botId, groupId));
        }
        // 能力协议层（Worker 无发言协议——发言协议片段 form_fit 不含 worker）
        LayerRender protocol = renderLayer(PromptFragmentDO.LAYER_PROTOCOL, form, variables);
        layers.add(protocol.text());
        fragmentVersion = Math.max(fragmentVersion, protocol.maxFragmentVersion());
        // 任务层（Turn 无）
        if (taskLayerText != null && !taskLayerText.isBlank()) {
            layers.add("【任务】\n" + taskLayerText);
        }
        String prompt = String.join("\n\n",
                layers.stream().filter(layer -> layer != null && !layer.isBlank()).toList());
        return new AssembleOutcome(prompt, fragmentVersion, personaVersion);
    }

    /**
     * 群上下文层组装：群名/平台/成员概况/群规——内容整体 XML 定界注入（群配置与群友
     * 相关数据均是不可信数据，US7 不可信变量通道）。成员概况来自群体画像（粗粒度、
     * 不随发言人变化——逐人态度走现注区，两个注入点职责分立）。
     */
    private String renderGroupContext(String platform, String botId, String groupId) {
        GroupDO group = groupMapper.selectByGroup(platform, botId, groupId);
        GroupConfigDO config = groupConfigMapper.selectByGroup(platform, botId, groupId);
        StringBuilder body = new StringBuilder();
        body.append("- 群名：").append(group != null && group.getGroupName() != null
                ? group.getGroupName() : "未知").append('\n');
        body.append("- 平台：").append(platform).append('\n');
        body.append("- 成员概况：").append(renderMemberDigest(platform, botId, groupId)).append('\n');
        if (config != null && config.getGroupRules() != null && !config.getGroupRules().isBlank()) {
            body.append("- 群规：").append(config.getGroupRules()).append('\n');
        }
        return "【群上下文】\n" + (delimitGuard != null
                ? delimitGuard.wrap(body.toString(),
                cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VARIABLE)
                : cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(body.toString(),
                cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VARIABLE));
    }

    /**
     * 成员概况渲染（T032）：活跃成员 + 常聊话题，来自 group_profile 的
     * active_members / topic_dist——粗粒度概况一行（最近谁常在、最近聊什么），
     * 不带逐人态度（那归现注区态度行）。画像未生成时仍回「暂无」。
     */
    private String renderMemberDigest(String platform, String botId, String groupId) {
        try {
            cn.edu.scut.chatbot.memory.profile.GroupProfileDO profile =
                    groupProfileMapper.selectByGroup(platform, botId, groupId);
            if (profile == null) {
                return "暂无（画像尚未生成）";
            }
            List<String> members = topKeys(profile.getActiveMembers(), 5);
            List<String> topics = topKeys(profile.getTopicDist(), 3);
            if (members.isEmpty() && topics.isEmpty()) {
                return "暂无（画像尚未生成）";
            }
            StringBuilder digest = new StringBuilder();
            if (!members.isEmpty()) {
                digest.append("常在群里的有 ").append(String.join("、", members));
            }
            if (!topics.isEmpty()) {
                if (digest.length() > 0) {
                    digest.append("；");
                }
                digest.append("最近常聊 ").append(String.join("、", topics));
            }
            return digest.toString();
        } catch (Exception e) {
            return "暂无（画像读取失败）";
        }
    }

    /**
     * 计数 JSON（{"key":count,...}）按值降序取前 N 个 key
     */
    private static List<String> topKeys(String countJson, int limit) {
        if (countJson == null || countJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readTree(countJson);
            List<Map.Entry<String, Integer>> entries = new ArrayList<>();
            node.fields().forEachRemaining(entry -> entries.add(
                    Map.entry(entry.getKey(), entry.getValue().asInt(0))));
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : entries) {
                if (keys.size() >= limit) {
                    break;
                }
                keys.add(entry.getKey());
            }
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 身份层渲染结果：文本 + 片段最大版本 + 人格版本（版本三键取数）
     */
    private record IdentityRender(String text, long maxFragmentVersion, long personaVersion) {
    }

    /**
     * 身份层渲染（T037 人格消费）：片段层之上追加变体与情绪素材。
     * 两条纪律：抽中变体文本渲染进 systemPrompt、权重数值与其余变体不出现；
     * 注意力偏好参数（健谈度/主动性等数值）不进 systemPrompt——本方法结构性不渲染它们。
     */
    private IdentityRender renderIdentityLayer(RunConfig.ExecutionForm form,
                                               Map<String, String> variables,
                                               String platform, String botId, String groupId) {
        LayerRender base = renderLayer(PromptFragmentDO.LAYER_IDENTITY, form, variables);
        PersonaDO persona;
        try {
            persona = personaResolver.resolve(platform, botId, groupId);
        } catch (Exception e) {
            log.warn("人格解析失败回默认人格组装（Run 不因人格缺失失败）", e);
            persona = null;
        }
        long personaVersion = persona != null && persona.getVersionNo() != null
                ? persona.getVersionNo() : 0;
        if (persona == null) {
            return new IdentityRender(base.text(), base.maxFragmentVersion(), 0);
        }
        PersonaRuntime runtime = PersonaRuntime.parse(persona);
        StringBuilder builder = new StringBuilder(base.text());
        PersonaRuntime.VariantDraw draw = drawVariantOf(runtime);
        if (!draw.noVariant() && !draw.text().isBlank()) {
            builder.append("\n[当前表达变体]\n").append(draw.text()).append('\n');
        }
        if (!runtime.emotion().semanticCues().isEmpty()) {
            builder.append("\n[情绪雷点与回弹素材（供 updateEmotion 自报判断）]\n");
            for (String cue : runtime.emotion().semanticCues()) {
                builder.append("- ").append(cue).append('\n');
            }
        }
        return new IdentityRender(builder.toString(), base.maxFragmentVersion(), personaVersion);
    }

    /**
     * 变体抽取点（Run 启动组装时抽一次、随 systemPrompt 快照 Run 内固定）。
     * 包可见——测试子类可覆写固定抽取结果做确定性断言。
     */
    PersonaRuntime.VariantDraw drawVariantOf(PersonaRuntime runtime) {
        return runtime.drawVariant();
    }

    /**
     * 单层渲染结果：文本 + 该层实际用到的片段最大版本号
     */
    private record LayerRender(String text, long maxFragmentVersion) {
    }

    /**
     * 单层片段渲染：published 片段按 form_fit 过滤后拼接 + 变量替换
     */
    private LayerRender renderLayer(String layer, RunConfig.ExecutionForm form, Map<String, String> variables) {
        List<PromptFragmentDO> fragments = fragmentMapper.selectPublishedByLayer(layer);
        StringBuilder rendered = new StringBuilder();
        long maxVersion = 0;
        for (PromptFragmentDO fragment : fragments) {
            if (!fitsForm(fragment.getFormFit(), form)) {
                continue;
            }
            if (fragment.getVersionNo() != null) {
                maxVersion = Math.max(maxVersion, fragment.getVersionNo());
            }
            rendered.append(renderVariables(fragment.getContent(), variables)).append("\n\n");
        }
        // 能力协议层附加技能目录（目录常驻一行一条）
        if (PromptFragmentDO.LAYER_PROTOCOL.equals(layer)) {
            String directory = skillDirectoryRenderer.renderDirectory(variables.get("__skillContext__"));
            if (directory != null && !directory.isBlank()) {
                rendered.append(directory);
            }
        }
        return new LayerRender(rendered.toString().trim(), maxVersion);
    }

    /**
     * 形态适配：form_fit 逗号分隔多值（all 或包含当前形态即适配）
     */
    private boolean fitsForm(String formFit, RunConfig.ExecutionForm form) {
        if (formFit == null || formFit.isBlank() || "all".equals(formFit)) {
            return true;
        }
        for (String fit : formFit.split(",")) {
            String normalized = switch (form) {
                case TURN -> "turn";
                case LIGHT_TASK -> "lightTask";
                case WORKER -> "worker";
            };
            if (normalized.equals(fit.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 变量集组装（身份层素材：人格名；技能目录过滤上下文：群级禁用技能清单）
     */
    private Map<String, String> buildVariables(String platform, String botId, String groupId) {
        Map<String, String> variables = new LinkedHashMap<>();
        try {
            PersonaDO persona = personaResolver.resolve(platform, botId, groupId);
            if (persona != null) {
                variables.put("persona.name", persona.getName());
                variables.put("persona.selfSetting", personaResolver.selfSettingOf(persona));
            }
        } catch (Exception e) {
            log.warn("人格解析失败回默认变量组装（Run 不因人格缺失失败）", e);
        }
        variables.put("__skillContext__", disabledSkillsOf(platform, botId, groupId));
        return variables;
    }

    /**
     * 群级禁用技能清单 → 技能目录过滤上下文（"skill1,skill2" 逗号分隔；空清单为 null 不过滤）
     */
    private String disabledSkillsOf(String platform, String botId, String groupId) {
        if (groupId == null) {
            return null;
        }
        GroupConfigDO config = groupConfigMapper.selectByGroup(platform, botId, groupId);
        if (config == null || config.getDisabledSkills() == null
                || "[]".equals(config.getDisabledSkills())) {
            return null;
        }
        try {
            List<String> disabled = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readValue(
                    config.getDisabledSkills(),
                    cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().getTypeFactory()
                            .constructCollectionType(List.class, String.class));
            return disabled == null || disabled.isEmpty() ? null : String.join(",", disabled);
        } catch (Exception e) {
            log.warn("群级禁用技能清单解析失败按空处理", e);
            return null;
        }
    }

    /**
     * 朴素变量替换：未定义渲染为空并告警（不中断组装）
     */
    private String renderVariables(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        Matcher matcher = VARIABLE_REFERENCE.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (value == null) {
                log.warn("提示词变量未定义渲染为空：{}（模板片段变量声明应拦在保存前）", matcher.group(1));
                value = "";
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
