package cn.edu.scut.chatbot.contract;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 守护纪律：contract 纯净（宪法第五条 ArchUnit 规约之一，设计方案 3.1.3）。
 * 契约模块是接入层与主服务唯一的共享面，只允许 record 定义与常量、只依赖 JDK——
 * 一旦有人往这里塞框架注解或中间件类型，两端就被焊死在同一套运行时上，跨进程解耦失效。
 */
@AnalyzeClasses(packages = "cn.edu.scut.chatbot.contract", importOptions = ImportOption.DoNotIncludeTests.class)
class ContractPurityArchTest {

    /**
     * main 源码集的任何类不得依赖 JDK（java.. / javax..）与 contract 自身包以外的任何包，
     * 违反即构建失败。contract 内部各包（envelope / message / in / out / error）互相引用是
     * 契约自身的结构组成，不在此限。
     */
    @ArchTest
    static final ArchRule contractMainClassesOnlyDependOnJdkAndItself = noClasses()
            .that().resideInAPackage("cn.edu.scut.chatbot.contract..")
            .should().dependOnClassesThat(
                    resideOutsideOfPackage("java..")
                            .and(resideOutsideOfPackage("javax.."))
                            .and(resideOutsideOfPackage("cn.edu.scut.chatbot.contract..")))
            .because("contract 模块零框架依赖（宪法第二条/第五条）："
                    + "它同时进接入层与主服务两个进程的 classpath，引入任何第三方依赖都会强制两端共同承担");
}
