# ArchUnit 架构门禁怎么立

## 这一步要解决什么

依赖方向画在图上不会自动阻止下一次 import。初始骨架能编译，只证明当前代码使用了已声明的依赖，不证明 core 没有偷偷长出 HTTP、配置读取或第二个 JDK 兼容层。这一步把最重要的禁止关系变成普通 JUnit 测试，让越界代码在进入评审前就让构建失败。

## 先想清楚再写

架构门禁最容易犯的错误，是先写一句很宏大的“core 必须保持干净”，再不知道怎样证明它。Java 编译器不在乎一个配置类为什么读取环境变量，也不在乎 ThreadProvider 为什么回头依赖生命周期引擎；这些关系在类型上完全合法。ArchUnit 的价值就是把设计语言翻译成可匹配的类、包、方法调用和依赖边，让“合法但不应发生”的事也有失败出口。

规则还必须匹配真实的绕过形状。以 `a55be81`（`fix: 修补配置边界规则的匹配漏洞`）为例，原规则只写了 `callMethod(System.class, "getenv")`。在 ArchUnit 的方法调用匹配里，这描述的是无参重载，并不会自动覆盖 `System.getenv("KEY")`。可实际开发中最可能出现的偏偏是带键读取：某个组件为了省事直接拿一个环境变量，规则却全部通过。修复把无参、`getenv(String)`、`getProperties()`、`setProperties(Properties)` 分别列出，另加全仓库禁止 `setProperty` 与 `clearProperty` 的规则。这里最重要的教训不是“多写几个重载”，而是每加一条规则都要问：我匹配的是 API 家族，还是恰好手边那一种调用签名？

这也解释了为什么配置来源只能由 config 包读取，却仍需要更细的 DI 规则。包级禁止能挡住 `System.getenv`，挡不住 `System.getProperty("barezen.something")` 写进工厂。当前架构测试因此还有 `di_never_reads_a_configuration_value`，同时覆盖 `getProperty(String)` 和带默认值的重载。设计上的正确数据流是 Configuration → typed config → assembly → constructor；只检查 import 或只检查一个包，会留下大量同包绕行空间。

不要用规则数量代替规则有效性。`bfef5d5` 的提交标题是“用 24 条架构规则约束适配器边界”，实际新增的主体是 546 行的 `BarezenHttpJdkArchitectureTest`，core 架构测试只改了 3 行。这个区分很重要：不能把 24 当成 core 规则数，更不能为了显得严谨而复制一堆同义规则。规则应各自对应一种越界方式，并在失败信息中说明违反了什么。

`allowEmptyShould(true)` 也不是“关闭保护”。它允许某些被禁止的类型尚未出现在 classpath 时规则正常通过。例如当前 core 不引用 `ScopedValue` 或 `StructuredTaskScope`，没有匹配对象是预期状态；一旦有人引入这些类型，依赖边就会出现并让规则失败。相反，如果一条依赖规则要求“必须存在某个目标”，却错误地允许空集合，它就可能在没有实现时也绿掉。写规则前先判断：这里要禁止一个关系，还是要求一个关系确实存在？

最后，门禁不能替代字节码检查。ArchUnit 看到的是编译后的类与依赖，release 17 编译错误、命令行把输出变成 major 65、旧 class 混入 target，都不一定表现为一条新的非法依赖。模块 POM、ArchUnit、class-major 脚本分别守声明、结构图和产物，它们不是三份重复工作。

## 动手

先建立最小规则集，而不是等所有模块出现后才补。初始提交 `2670984` 已经带来 [BarezenCoreArchitectureTest.java](../../framework/barezen-core/src/test/java/com/barezen/core/BarezenCoreArchitectureTest.java)，当时该文件 115 行；它与根 POM、core、能力探测和 `check-jdk-baseline.sh` 同批落地。也就是说，“先立边界再扩张”不是后来的装饰，而是骨架的一部分。

当前 core 规则文件可以直接作为阅读入口，但不要一次复制整个文件。先抓住三类形状。第一类限制外部依赖：

```java
@ArchTest
static final ArchRule core_depends_only_on_jdk_and_slf4j = noClasses()
    .should().dependOnClassesThat(dependencyOutsideAllowedBase())
    .as("barezen-core must not acquire any third party dependency beyond slf4j-api");

private static DescribedPredicate<JavaClass> dependencyOutsideAllowedBase() {
  return resideOutsideOfPackages(
      "java..", "javax..", "com.barezen.core..", "org.slf4j..");
}
```

这里允许 JDK、core 自己和 SLF4J API。应用若想使用 Logback，可以在自己的运行 classpath 选择实现，但不能把 logback-classic 变成 core 的编译依赖。规则检查依赖类，不检查消费者是否“实际上用到了”某个传递依赖，因此它比人工读 import 更稳定。

第二类把新 JDK 探测限制在一个包：

```java
@ArchTest
static final ArchRule newer_jdk_probing_is_confined_to_runtime = noClasses()
    .that().resideOutsideOfPackages(RUNTIME)
    .should().dependOnClassesThat().resideInAnyPackage("java.lang.invoke..")
    .as("only com.barezen.core.runtime may use MethodHandle style runtime probing");
```

`RUNTIME` 的真实值是 `com.barezen.core.runtime..`。这条规则并不宣称“MethodHandle 是危险的”；它只允许 ADR-0010 批准的那一个 seam。以后若真需要第二个新 JDK 能力，正确动作是增加消费者、测试和 ADR，再决定是否扩大边界，而不是在业务包里悄悄复制一份 probe。

第三类必须具体到方法调用和重载。以下是 `a55be81` 修复后的核心形状：

```java
@ArchTest
static final ArchRule only_config_reads_environment_and_property_tables = noClasses()
    .that().resideOutsideOfPackages(CONFIG)
    .should().callMethod(System.class, "getenv")
    .orShould().callMethod(System.class, "getenv", String.class)
    .orShould().callMethod(System.class, "getProperties")
    .orShould().callMethod(System.class, "setProperties", java.util.Properties.class)
    .as("configuration sources live only in com.barezen.core.config");
```

还有一条独立规则禁止任何位置调用 `setProperty(String, String)` 或 `clearProperty(String)`。为什么不把写操作也放进“config 可读”规则？因为允许 config 读取输入，不等于允许 config 修改全局输入。读与写是不同设计承诺，合成一条规则会意外给错误操作发通行证。

加入 DI 后，`e2c9902` 同批扩充了 core 架构测试，`79790a5` 又修复装配失败时的释放行为。教程在这里只提前指出门的种类，不把 DI 实现搬来：依赖图、生命周期、事件总线、插件 SPI 都在后续提交拥有自己的规则。每增加一个真实子系统，就增加它对应的禁止关系，而不是先预测所有未来规则。

新规则要有“会红”的证据。安全的做法是在临时 worktree 或可丢弃副本里加入一个明确违规调用，运行该架构测试确认失败，再移除探针；不要在用户正在编辑的文件上做破坏性验证。至少分别验证“目标违规会失败”和“移回合法代码会通过”。只看到合法仓库全绿，不能证明规则本身有牙。

## 常见的第一次写错

- **错误：只写 `callMethod(System.class, "getenv")`。** 正确：逐个覆盖实际会调用的重载；`a55be81` 正是这个漏口的修复证据。
- **错误：把配置读取和配置写入放在同一条允许规则里。** 正确：读取限制在 config，写入全局属性表一律禁止。
- **错误：用 `allowEmptyShould(true)` 凑一条“必须有实现”的规则。** 正确：先分清禁止关系和要求关系，后者不能允许空匹配。
- **错误：把 `bfef5d5` 的 24 条说成 core 已有 24 条。** 正确：该提交新增的是 JDK adapter 的架构测试文件，core 只做小幅修改。
- **错误：看到绿色测试就认为覆盖完整。** 正确：对关键规则做可恢复的变异探针；没有命中失败消息的规则只能算候选。

## 怎么确认这一步没造错

先运行 core 架构测试，再联合线程策略和 class-major 契约，确保结构规则与运行期能力没有互相拆台：

```bash
mvn -o -B -ntp -pl framework/barezen-core -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ThreadProvidersTest,BarezenCoreArchitectureTest,ClassMajorContractTest
```

本次于 2026-11-24 实际执行，目标 core 合计 38 项测试，`Failures: 0, Errors: 0, Skipped: 0`， reactor 为 `BUILD SUCCESS`。其中 `BarezenCoreArchitectureTest` 为 21 项。`-am` 会让上游一起构建；`failIfNoSpecifiedTests=false` 只避免上游没有同名测试类时误报，不会把 core 的失败变成成功。

对照修复提交时，可以直接查看真实差异，不需要猜测当年改了哪里：

```bash
git show --format=fuller --stat a55be81
git show --unified=12 a55be81 -- \
  framework/barezen-core/src/test/java/com/barezen/core/BarezenCoreArchitectureTest.java
git show --stat bfef5d5
```

应看到 `a55be81` 除 README 和 ADR 文档外修改了 core 架构测试，`bfef5d5` 新增 JDK adapter 架构测试 546 行。若你复刻这些提交，架构测试应作为正常 `test` 阶段运行，而不是只存在于本地脚本里。

## 此刻的代码长什么样

现在骨架不仅“能编译”，还能拒绝 core 引入未批准依赖、第二个 MethodHandle 探测点、非 config 位置读取进程配置、DI 自行读取环境值等越界。规则会随真实模块继续增加，但当前这些已经足以保护生命周期、配置和运行期能力不被上层概念反向渗透。

下一步检查整条基线。声明检查只能说明 POM 写了 release 17，ArchUnit 只能说明当前类图符合规则；真正交付的 class 文件仍必须逐字节确认 major 61。最后一个检查点会把三者放到一次可重复执行的流程里，并解释为什么“构建成功”仍然不足以单独证明兼容性。
