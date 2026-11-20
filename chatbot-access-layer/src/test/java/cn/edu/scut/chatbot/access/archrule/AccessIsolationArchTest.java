package cn.edu.scut.chatbot.access.archrule;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 守护纪律：接入隔离（宪法第二条「接入层与主服务零业务依赖」+ 宪法第五条 ArchUnit 规约）。
 * 接入层是业务薄进程：对主服务（agent-core）与沙箱的全部业务知识为零，
 * 唯一共享面是 chatbot-contract 的纯数据契约——任何绕过 contract 直连业务类的路径
 * 都会让接入层染上业务决策，破坏「翻译、保真、协议事务」的薄定位。
 *
 * <p>契约自身不被绕过直连中间件：contract 模块的纯净规则（contract 模块测试域）连第三方依赖
 * 都不允许存在，本模块无需重复守护。
 */
@AnalyzeClasses(packages = "cn.edu.scut.chatbot", importOptions = ImportOption.DoNotIncludeTests.class)
class AccessIsolationArchTest {

    /**
     * 接入层不得依赖 contract 与自身以外的任何 cn.edu.scut.chatbot 包（agent-core / sandbox 的全部业务包都在此列）
     */
    @ArchTest
    static final ArchRule accessLayerMustOnlyTouchContract = noClasses()
            .that().resideInAPackage("cn.edu.scut.chatbot.access..")
            .should().dependOnClassesThat(
                    resideInAPackage("cn.edu.scut.chatbot..")
                            .and(resideOutsideOfPackage("cn.edu.scut.chatbot.contract.."))
                            .and(resideOutsideOfPackage("cn.edu.scut.chatbot.access..")))
            .because("接入隔离（宪法第二条）：接入层对主服务业务知识为零，唯一共享面是 contract 纯数据模块");
}
