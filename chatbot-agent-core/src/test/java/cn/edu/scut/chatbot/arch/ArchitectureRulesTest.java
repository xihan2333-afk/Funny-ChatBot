// 守护纪律：依赖纪律不靠口头约定（宪法第五条——ArchUnit 固化包依赖规约，违反即构建失败）
package cn.edu.scut.chatbot.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit 包依赖规约测试（宪法第五条：依赖纪律不靠口头约定——违反即构建失败）。
 *
 * <p>本 feature 新增两条规则（002 tasks T023）：
 * <ul>
 *   <li>内核纯净：loop 包不依赖 engine / memory / tool / skill / prompt / context 任何业务包
 *       ——工具与钩子全部经 loop 自定义接口挂入，内核拿到的永远是流内消息而不是异常；</li>
 *   <li>management 收口：management 包只依赖门面接口，不触达 engine 内部组件类
 *       ——管理平台不得持有第二条写状态通道。</li>
 * </ul>
 * 另两条规则（contract 纯净、接入隔离）在各自模块测试域执行。
 */
class ArchitectureRulesTest {

    /**
     * 守护纪律：循环内核纯净（宪法第五条、设计方案 2.2.2）；随实现推进持续护航
     */
    @Test
    void loop包不依赖任何业务包_内核纯净() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cn.edu.scut.chatbot");

        ArchRule kernelPurity = noClasses()
                .that().resideInAPackage("..chatbot.loop..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..chatbot.engine..",
                        "..chatbot.memory..",
                        "..chatbot.tool..",
                        "..chatbot.skill..",
                        "..chatbot.prompt..",
                        "..chatbot.context..",
                        "..chatbot.management..",
                        "..chatbot.llm..",
                        "..chatbot.observability..",
                        "..chatbot.security..",
                        "..chatbot.infrastructure..",
                        "..chatbot.bootstrap..")
                .allowEmptyShould(true) // 空跑阶段（loop 尚无类）允许；随实现推进规则生效
                .because("loop 是零业务依赖的推理内核：模型调用契约（StreamFn）、工具契约"
                        + "（AgentTool）、钩子全部是 loop 自己定义的接口，业务包是它们的实现方"
                        + "——依赖方向永远是别人实现 loop 的接口");

        kernelPurity.check(classes);
    }

    /**
     * 守护纪律：management 收口（宪法第五条）；management 只写配置数据与调用领域门面接口
     */
    @Test
    void management包不触达engine内部组件_收口() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cn.edu.scut.chatbot");

        ArchRule notIntoEngine = noClasses()
                .that().resideInAPackage("..chatbot.management..")
                .should().dependOnClassesThat().resideInAPackage("..chatbot.engine..")
                .allowEmptyShould(true) // 空跑阶段（management 尚无类）允许
                .because("management 只依赖门面接口（engine-commands.md 契约），"
                        + "不得触达领域内部组件类——不得持有第二条写状态通道");

        ArchRule notIntoTool = noClasses()
                .that().resideInAPackage("..chatbot.management..")
                .should().dependOnClassesThat().resideInAPackage("..chatbot.tool..")
                .allowEmptyShould(true)
                .because("工具体系的配置面经 tool_config 数据与门面，不经 management 直连组件");

        notIntoEngine.check(classes);
        notIntoTool.check(classes);
    }

    /**
     * 守护纪律：management 收口（宪法第五条）；005 起观测安全域（observability / security）
     * 的 DO 与 Mapper 同样不得被管理面触达——数据面只经领域门面。
     *
     * <p>说明：observability → engine / loop / llm 是本 feature 的合法消费边
     * （事件桥消费事件、ES 查询读事实源），不新增被规约边；loop 不依赖
     * observability / security 已由内核纯净规则覆盖（该规则禁依赖清单含两包）。
     */
    @Test
    void management包不触达观测安全域的DO与Mapper_数据面收口() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cn.edu.scut.chatbot");

        ArchRule notIntoDoOrMapper = noClasses()
                .that().resideInAPackage("..chatbot.management..")
                .should().dependOnClassesThat(
                        resideInAnyPackage("..chatbot.observability..", "..chatbot.security..")
                                .and(simpleNameEndingWith("DO").or(simpleNameEndingWith("Mapper"))))
                .allowEmptyShould(true)
                .because("management 只调用领域门面接口（observe-query / eval-and-flywheel / "
                        + "security-gate / admin-auth 契约），不得触达 DO / Mapper——"
                        + "管理平台不得持有第二条写状态通道");

        notIntoDoOrMapper.check(classes);
    }
}
