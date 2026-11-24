# 第 2 章：设计原则、空仓库与基线

## 本章目标

第二章建立框架的构建骨架，但刻意不写 HTTP。先决定一个 jar 承诺兼容哪个 JDK，再处理虚拟线程这种只在 JDK 21+ 出现的运行期能力；随后划分模块，并让依赖方向与字节码基线由脚本和测试守住。这样后续每加一层，都不需要重新争论项目对自己的基本承诺。

最终状态不是“空目录”，而是最小可建造骨架：父 POM、core、配置基线、能力探测入口、架构测试和两套校验脚本。它还不能服务请求，却已经能拒绝高版本字节码、JDK 专属模块、core 对 HTTP 的反向依赖，以及第二处不受约束的新 JDK 探测。

## 涉及 ADR

- [ADR-0002：JDK 17 基线与运行期能力](../../docs/adr/0002-jdk-17-baseline-with-runtime-capability-instead-of-jdk-modules.md)：所有模块由父 POM 统一编译为 release 17，一个产物服务所有支持的 JDK。Java 21 的虚拟线程通过运行期探测选择，不建立 barezen-jdk21 模块或多版本 jar。代价是保留受限的 MethodHandle 间接层，并在 JDK 升级时重新审查它。
- [ADR-0008：没有模块内容就不建聚合模块](../../docs/adr/0008-no-aggregator-pom-without-modules.md)：空的 barezen-runtime 被删除，目录只有拥有可构建内容或真实子模块时才成为 Maven 模块。设计图是预测，不是构建输入。这样 reactor 输出只显示真实工作，不会用占位 POM 制造完成度。
- [ADR-0032：Class Major 是构建契约](../../docs/adr/0032-class-major-build-contract.md)：声明 release 17 与产物 major 61 是两项不同事实，因此用模块测试和仓库脚本分别验证。脚本扫描整个树的 class 文件，并拒绝零检查与异常输出位置。命令行覆盖、插件误配或混入旧字节码都会在构建或 CI 中暴露。
- [ADR-0001：core 唯一编译第三方依赖是 SLF4J API](../../docs/adr/0001-core-third-party-dependency-is-slf4j-api-only.md)：core 不选择日志实现，只使用 org.slf4j API。任何新增编译依赖都需要新 ADR，ArchUnit 与 Maven enforcer 共同守住边界。代价是应用必须选择绑定，core 也不能借第三方工具简化内部实现。
- [ADR-0010：运行期能力探测的范围](../../docs/adr/0010-runtime-capability-seam-scope.md)：能力快照只记录已有消费者的字段，新 JDK API 的反射式探测集中在一个类。没有消费者的 TLS、ALPN 等字段不会提前加入。这样既能保持 JDK 17 可加载，又避免探测层自己变成通用兼容框架。

## 步骤导航

1. [JDK 17 字节码基线与运行期能力探测](01-JDK17字节码基线与运行期能力探测.md)：从 `2670984` 的父 POM 与三字段快照出发，把编译承诺和运行选择拆成两个独立入口。
2. [为什么不用 --enable-preview 与内部模块](02-为什么不用--enable-preview与内部模块.md)：沿着 `VirtualThreadProbe` 的真实查找与 invoke 路径，解释一个受限 MethodHandle 桥接器的收益和代价。
3. [模块划分与依赖方向](03-模块划分与依赖方向.md)：从初始只有 parent、BOM、core 的 reactor，追到 web、两个 adapter、plugins 和 testkit 逐步加入的真实提交。
4. [ArchUnit 架构门禁怎么立](04-ArchUnit架构门禁怎么立.md)：以 `a55be81` 的重载漏口和 `bfef5d5` 的适配器规则文件为证据，把禁止关系写成会失败的测试。
5. [检查点：空仓库与门禁就位](05-检查点-空仓库与门禁就位.md)：用 `4c9feda` 建立的产物契约，分别检查声明、core 类图和全树 major 61。

## 本章提交地图

本章不是把下面每个提交伪装成一次独立重构，而是把真实历史中交织的工作拆成便于理解的建造顺序。建议一边读，一边在仓库执行 `git show --stat <hash>`，再看自己关心的文件：

| 提交 | 本章要观察的变化 | 不要误读成什么 |
| --- | --- | --- |
| `2670984`：实现应用生命周期内核 | 根 POM、BOM、core、release 17、能力 probe、初始架构测试与声明脚本同批出现 | 不是现代九模块仓库的起点，也不是只建目录的提交 |
| `e0bc31b`：装配阶段一次性解析配置 | 配置开始在装配时形成快照，为后续 config 边界提供上下文 | 不是 ThreadProvider 自身读取环境变量 |
| `e2c9902`：显式装配替代反射与类路径扫描 | core 内增加 DI，并扩充架构规则 | 不是引入 Spring 式注解容器 |
| `a55be81`：修补配置边界规则的匹配漏洞 | 补齐 `getenv(String)` 等重载，并禁止写系统属性 | 不是只改文档或只改一条 import 规则 |
| `32ebc41`：定义不依赖具体服务器的 HTTP 契约 | web 模块与自身架构测试进入 reactor | 不是让 core 开始理解 HTTP |
| `6ef05b9`：定义 HTTP 服务器适配器契约 | 契约与实现之间出现明确端口 | 不是立即启动真实端口 |
| `a2e05fa`：实现 JDK HttpServer 适配器 | http-jdk 成为独立实现模块 | 不是把 JDK server 类型泄漏进 web 契约 |
| `bfef5d5`：用 24 条架构规则约束适配器边界 | 新增 546 行 http-jdk 架构测试，core 规则只改 3 行 | 24 不是 core 的规则总数 |
| `50cd3cc`：支持在 Servlet 容器内运行路由 | http-servlet 加入，验证第二个适配器方向 | 不是用 Servlet 替换 JDK 实现 |
| `68e0c67`、`743ae96`：首批插件与 TestKit | plugins 和 testkit 分别成为真实产物 | 不是提前创建空 aggregator |
| `4c9feda`：字节码版本校验进入构建与 CI | 三个模块测试、全树脚本和 CI 步骤共同证明 major 61 | 不是再次声明一遍 release 17 |

本章的检查点已于 2026-11-24 实际执行。`mvn -pl framework/barezen-core -am test` 的指定测试合计 38 项通过；全树 `scripts/check-class-major.sh` 检查 646 个现有 class，全部为 major 61。646 是当时的实测数量，不是常量。完整交付顺序仍是先声明检查，再 `clean verify`，最后检查刚生成的产物；文档写作没有改动用户 WIP，也没有用全仓 clean 干扰它。

## 本章完成后的能力清单

你应能解释为什么 `maven.compiler.release=17` 同时约束语言、API 和字节码，而 `java -version` 只说明构建主机。你能用 `RuntimeCapabilities.detect()` 读取当前 JVM 的能力快照，并理解 `ThreadPolicy.AUTO` 在可用时选择虚拟线程、不可用时回退平台线程，显式 VIRTUAL 则必须失败。

你还应能从根 POM 列出真实模块，指出 core 不依赖 web、HTTP 或 SQL，TestKit 与 examples 是消费者叶子。架构测试不再只是文档里的推荐，而是能在新 import 或新调用出现时让构建失败。最后，你能分别运行声明检查与 class-major 检查，并知道为什么只运行其中一个不够。

## 不这样设计会坏掉什么

如果把开发机 JDK 21 直接写成项目基线，JDK 17 用户首先失去加载能力；如果反过来复制 JDK 17/21 两套源码，公共修复需要在两处同步，漏掉一处只会在特定运行环境暴露。内部 API 或 preview 方案还会绑定具体厂商和版本，升级时可能突然消失。

如果通过命令行提高 release，POM 基线脚本仍可能通过，因为它检查的是声明而不是产物。若 class-major 脚本在零文件时返回成功，错误构建也能蒙混过关；若它只扫描已知模块目录，新模块或误放的 class 文件就会被遗漏。模块占位符同样危险：它让依赖图看起来完整，却没有可执行内容约束任何人。

## 下一章预告

第三章开始写生命周期。骨架已经能保证依赖与字节码不漂移，接下来要解决的是“对象何时可用”：状态机有哪些状态，为什么所有 initialize 必须早于任何 start，启动失败如何逆序释放，停止为什么不能简单抛异常。到第三章结束，第一个能被启动、回滚和关闭的应用骨架才会出现。
