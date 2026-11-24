# 配置与装配：快照、显式工厂与生命周期边界

## 这份文档讲什么

`barezen-core` 把启动前的工作拆成三个互不替代的部分：

- `Configuration` 读取并转换配置值，得到构造时快照。
- `ServiceAssembly` 根据应用注册的工厂创建对象图，并释放自己创建的资源。
- `LifecycleOrchestrator` 驱动已经构造好的对象完成初始化、启动、停止和启动回滚。

应用负责按顺序把它们接起来。框架没有 `BareZenApplication.run(...)`，也没有一个同时持有配置、容器和生命周期引擎的万能上下文。这个边界对应：

- [ADR-0011：ThreadProvider 是带所有权语义的工厂](../adr/0011-thread-provider-is-a-factory-with-ownership.md)。
- [ADR-0013：配置在装配时解析](../adr/0013-configuration-is-resolved-at-assembly-time.md)。
- [ADR-0014：依赖注入是显式工厂注册](../adr/0014-dependency-injection-is-explicit-factory-registration.md)。
- [ADR-0015：应用装配是应用的职责，不是框架组件](../adr/0015-application-assembly-is-a-responsibility-not-a-component.md)。

需要特别注意：ADR-0015 取代了 ADR-0013 第 6 项中的早期设计。当前 `LifecycleOrchestrator.Builder` 没有 `configuration(Configuration)`，也没有 `threadKind()`。应用先解析 `RuntimeConfig`，再把 `ThreadPolicy` 交给 orchestrator。

## 1. 先固定职责，不要把所有事情塞进 Configuration

公开入口是：

```java
public interface Configuration {
    <T> Optional<T> get(ConfigurationKey<T> key);

    static Configuration of(ConfigurationSource... sourcesWeakestFirst)
    static Configuration inMemory(Map<String, String> values)
    static Configuration standard(Path propertiesFile)
}
```

`Configuration` 只回答“这个键是什么值”。它没有 `put`、`set`、`reload`，也没有 `get(Class<T>)`。后者会把配置对象变成按类型查找对象的 service locator，那是 `ServiceAssembly` 的职责，两者不能合并。

`ConfigurationSource` 的公开契约同样很小：

```java
public interface ConfigurationSource {
    String name();
    Optional<String> find(String keyName);
}
```

source 只提供原始字符串；合并顺序、类型转换和默认值由 `Configuration` 处理。框架自带的 source 实现是包私有的，应用通常使用 `inMemory` 或 `standard`，只有需要额外输入格式时才实现这个接口。

## 2. 键、类型和默认值必须一起声明

```java
public record ConfigurationKey<T>(String name, Class<T> type, T defaultValue) {
    public static <T> ConfigurationKey<T> of(String name, Class<T> type)
    public static <T> ConfigurationKey<T> of(String name, Class<T> type, T defaultValue)
}
```

组件应当把键声明成自己的常量，例如当前运行期配置使用：

```java
public static final ConfigurationKey<ThreadPolicy> THREADS =
    ConfigurationKey.of("barezen.runtime.threads", ThreadPolicy.class, ThreadPolicy.AUTO);
```

不要在业务代码里散落字符串字面量，也不要让多个组件各自解释同一个键。`ConfigurationKey` 同时固定名称、目标类型和缺省值，读取方不需要再次猜类型。

名称必须符合点分段格式：`[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*`。空名称、`app..port`、`.app` 和含空格的名称会在创建键时被拒绝；`type` 不能为 `null`。

默认值只在所有 source 都没有这个键时使用。它不用于修复一个存在但格式错误的值。

## 3. 合并规则是后来者覆盖，不是第一个命中

`Configuration.of(...)` 的参数名已经明确写出顺序：`sourcesWeakestFirst`。实现会反转参数，从最强 source 开始查找；因此越靠后的 source 优先级越高。

```java
Configuration configuration = Configuration.of(
        fileLayer,
        environmentLayer,
        systemPropertiesLayer);
```

优先级为：

```text
ConfigurationKey.defaultValue
    < 第一个 source
    < 后续 source
    < 最后一个 source
```

对于 `Configuration.standard(...)`，具体顺序是：

```text
key default < properties file < environment variables < system properties
```

这是逐键覆盖，不是整块替换。系统属性只覆盖它声明的键；文件里其他键仍然可读。高优先级 source 也不会与低优先级 source 拼接字符串。例如文件值是 `platform`，环境值是 `virtual`，系统属性值是 `platform`，最终就是系统属性的 `platform`。

不要在业务代码中依赖 `ConfigurationSource` Javadoc 中笼统的“first match wins”描述来判断整个配置的结果。真正的优先级由 `MergedConfiguration` 对 source 列表的搜索顺序决定，`ConfigurationPrecedenceTest` 对这条规则做了逐层验证。

空 source 列表会抛出 `IllegalArgumentException`，`null` source 会被拒绝。没有配置文件是合法状态，不代表必须给出一个空数组绕过标准入口。

## 4. 标准输入是快照，不是运行期热更新视图

`Configuration.standard(Path)` 在调用时依次接入：

1. 从指定路径读取的 UTF-8 `application.properties`。
2. 当前进程环境变量的快照。
3. 当前系统属性的快照。

```java
Configuration configuration = Configuration.standard(
        Path.of("application.properties"));
```

快照意味着：构造之后修改 `System.setProperty(...)`、修改传入的 map，或修改文件，都不会改变已经得到的 `Configuration`。需要新值时，应重新构造配置，并在应用装配阶段明确决定是否允许这样做；当前 API 没有 reload。

文件语义如下：

| 情况 | 行为 |
| --- | --- |
| 文件不存在 | 该 source 不贡献任何值，默认值和其他层继续工作 |
| 文件存在且可读 | 按 UTF-8 和 `java.util.Properties` 语法读取 |
| 路径不可读或解析失败 | 抛 `ConfigurationException`，不静默忽略 |
| 值转换失败 | 在 `get` 时抛 `ConfigurationException`，消息包含键、原值和 source 名称 |

“文件缺失合法”与“配置损坏必须失败”是两条不同的规则。前者允许只用默认值和环境部署，后者避免把权限错误或拼写错误伪装成正常启动。

环境名称只做一种映射：`.` 替换为 `_`，再使用 `Locale.ROOT` 转成大写。例如：

```text
barezen.runtime.threads -> BAREZEN_RUNTIME_THREADS
barezen.http.max-body-size -> BAREZEN_HTTP_MAX-BODY-SIZE
```

其中连字符保持不变。没有自动把 kebab-case 转成 snake-case、没有前缀剥离，也没有别名机制。系统属性直接使用键的原名，因此可以用 `-Dbarezen.runtime.threads=virtual` 覆盖文件和同键环境变量。

## 5. 值在 get 时转换，坏值不会回退

```java
Optional<String> name = configuration.get(
        ConfigurationKey.of("app.name", String.class));
Optional<Integer> port = configuration.get(
        ConfigurationKey.of("app.port", Integer.class));
Optional<Boolean> tls = configuration.get(
        ConfigurationKey.of("app.tls", Boolean.class));
Optional<Duration> timeout = configuration.get(
        ConfigurationKey.of("app.timeout", Duration.class));
```

支持的目标类型只有：

- `String`
- `Integer`
- `Long`
- `Boolean`
- `Duration`
- enum

`int.class`、`long.class`、`boolean.class` 等 primitive class 不受支持，应使用包装类型。复杂对象、集合和任意自定义转换器也不在当前 API 中；应用可以在 `Configuration` 之外显式解析它们。

转换规则：

| 类型 | 规则 |
| --- | --- |
| 所有类型 | 先去除原始字符串首尾空白 |
| `String` | 保留去除首尾空白后的内容；空字符串仍是已配置值 |
| `Integer` / `Long` | 按十进制整数解析 |
| `Boolean` | 仅接受忽略大小写的 `true` 或 `false` |
| `Duration` | 使用 ISO-8601，例如 `PT10S`，不是 `10s` |
| enum | 使用 `Locale.ROOT` 转大写后匹配常量，推荐配置写小写 |

空字符串不等于“缺失”。对于 `String`，即使键有默认值，显式空值也会返回空字符串；对于 `Integer`，空值会在 trim 后转换失败。

```java
ConfigurationKey<Duration> timeout =
    ConfigurationKey.of("app.timeout", Duration.class, Duration.ofSeconds(10));

// 文件中写 app.timeout=30s 时，这里抛 ConfigurationException，不会得到 10 秒默认值。
Optional<Duration> resolved = configuration.get(timeout);
```

转换失败抛 `ConfigurationException`。原转换异常通常作为 cause 保留，消息中能找到键名、原始值和 source 名称，足以定位是哪一层配置写错了。

## 6. 把配置解析成组件参数，不要把 Configuration 传下去

以线程策略为例，真实链路是：

```text
configuration value
    -> Configuration
    -> RuntimeConfig
    -> ThreadPolicy
    -> ThreadProvider
```

```java
RuntimeConfig runtimeConfig = RuntimeConfig.from(configuration);
ThreadPolicy threadPolicy = runtimeConfig.threads();
```

`RuntimeConfig` 是只含一个 `ThreadPolicy` 的 record。它不持有 `Configuration`，线程层也不提供 `create(Configuration, ...)`。解析决策留在应用装配代码，之后传递给组件的是明确的值。

这条规则适用于其他组件：组件构造函数接收端口、超时、布尔选项等参数，不接收整个配置快照，也不自行调用 `System.getenv` 或读取 `barezen.*` 系统属性。否则测试一个组件就要修改进程全局状态，依赖方向也会悄悄倒过来。

## 7. 字节码基线与运行期能力是两件事

根 POM 设置：

```xml
<maven.compiler.release>17</maven.compiler.release>
```

因此框架产物以 JDK 17 API 和字节码为基线。这不等于只在 JDK 17 上运行，也不等于直接引用 JDK 21 API。运行期能力由 `RuntimeCapabilities` 探测：

```java
RuntimeCapabilities runtime = RuntimeCapabilities.detect();
ThreadProvider threads = ThreadProviders.create(threadPolicy, runtime);
```

`RuntimeCapabilities` 当前记录一个实际能力 `virtualThreads`，以及 `javaFeature`、`vendor` 两个元数据字段。虚拟线程相关实现通过运行期探测使用，未编译为 JDK 17 也能直接访问的符号引用。

线程策略规则：

| 策略 | 运行期支持虚拟线程 | 运行期不支持虚拟线程 |
| --- | --- | --- |
| `AUTO` | 创建 virtual provider | 创建 platform provider |
| `PLATFORM` | 创建 platform provider | 创建 platform provider |
| `VIRTUAL` | 创建 virtual provider | 抛 `ThreadCapabilityUnavailableException`，不降级 |

因此“JDK 21+ 启用虚拟线程”只适用于默认 `AUTO` 且探测成功的情况。应用明确要求 `PLATFORM` 时不会自动升级，明确要求 `VIRTUAL` 时也不会静默降级。

`ThreadProvider` 是工厂，不是 executor 注册表：

```java
public interface ThreadProvider extends AutoCloseable {
    Kind kind();
    String name();
    boolean preferred();
    ExecutorService newBoundedExecutor(int maxConcurrency, String namePrefix);
    ExecutorService newUnboundedExecutor(String namePrefix);
    void close();
}
```

它按实例拥有自己创建的 executor。`close()` 幂等、不抛异常，并先尝试有序终止，超过预算后升级为强制终止。默认关闭预算是 10 秒。不要把一个 provider 当成全局 registry，也不要在 provider 关闭后继续创建 executor。

orchestrator 也可以接收外部 provider：

```java
LifecycleOrchestrator app = LifecycleOrchestrator.builder()
        .threads(threads)
        .register("worker", worker)
        .build();
```

即使 provider 来自外部，orchestrator 仍然拥有它的关闭。应用不应在 orchestrator 之外再关闭一次。

## 8. ServiceAssembly 只做显式工厂装配

```java
public final class ServiceAssembly implements Services, AutoCloseable {
    public static Builder builder()
    public <T> T require(Class<T> type)
    public <T> Optional<T> find(Class<T> type)
    public void close()

    public static final class Builder {
        public <T> Builder instance(Class<T> type, T object)
        public <T> Builder factory(
                Class<T> type, Function<Services, ? extends T> production)
        public ServiceAssembly build()
    }
}
```

`instance` 登记调用方已经创建的对象；`factory` 登记创建规则。注册本身不执行 factory，`build()` 才创建对象图。

```java
ServiceAssembly assembly = ServiceAssembly.builder()
        .instance(DataSource.class, dataSource)
        .factory(UserRepository.class, services ->
                new SqlUserRepository(services.require(DataSource.class)))
        .factory(UserService.class, services ->
                new UserService(services.require(UserRepository.class)))
        .build();
```

这段代码展示的是 API 形状；`SqlUserRepository`、`UserService` 和 `DataSource` 都由应用提供。仓库中的 `ServiceAssemblyTest` 使用同样的 `factory` 加 `services.require(...)` 结构验证构造注入。

真实行为如下：

- 一个 assembly 内，一个 `Class` 最多对应一个对象。
- 重复注册同一类型直接抛 `AssemblyException`，没有 `@Primary` 或 qualifier 可供选择。
- `build()` 急切创建全部已注册对象，不是等第一次请求才创建。
- 创建顺序由 factory 的依赖解析决定，不由注册行序决定；依赖先于依赖者创建。
- 缺失依赖、循环、factory 返回 `null`、结果类型不符都会在 `build()` 阶段失败。
- 循环错误包含完整路径，例如 `A -> B -> A`，不会等到 `StackOverflowError`。
- factory 自己抛出的 `RuntimeException` 或 `Error` 原样传播，不额外包装。
- 一个 builder 只能 `build()` 一次；空 assembly 合法。

它没有类路径扫描、注解处理、反射构造、`ServiceLoader` 发现、自动配置、request/session/prototype scope，也不读取配置。需要两个同接口实现时，应在应用代码中为它们选择不同的注册类型并明确组装，不能指望容器猜一个“主实现”。

## 9. 工厂只在构建期使用 Services，业务对象只保存依赖

factory 参数 `services` 是临时解析视图：

```java
.factory(UserService.class, services -> {
    UserRepository repository = services.require(UserRepository.class);
    return new UserService(repository);
})
```

正确做法是在 factory 内解析依赖，再把依赖作为构造参数交给业务对象。不要把 `services` 保存成字段，也不要让 Handler、插件或服务以后通过它继续 `require`。

`build()` 完成后，这个临时视图会拒绝查询并抛 `AssemblyException`，提示改用构造函数注入。完成的 `ServiceAssembly` 本身仍可通过 `require`/`find` 查询，供应用装配代码把对象注册到 orchestrator；它不是业务运行时应当持有的上下文。

这条限制防止 DI 退化为 service locator：依赖图应能从构造函数和注册代码读出来，而不是藏在某个业务的运行时查找里。

## 10. 所有权决定释放路径，两条路径不能互相代劳

| 对象来源 | 谁负责 `close()` | 生命周期参与者由谁驱动 `stop()` |
| --- | --- | --- |
| `instance` 注册 | 调用方 | 注册到 orchestrator 后由 orchestrator 驱动 |
| `factory` 创建且实现 `AutoCloseable` | `ServiceAssembly` | 注册到 orchestrator 后由 orchestrator 驱动 |
| `factory` 创建但不实现 `AutoCloseable` | 无自动 close | 注册到 orchestrator 后由 orchestrator 驱动 |
| 普通非参与者资源 | 按创建所有权处理 | 不适用 |

`ServiceAssembly.close()` 按创建逆序关闭自己创建的 `AutoCloseable`。一个关闭失败不会跳过后续对象；首个失败作为 `AssemblyException` 的 cause，其余失败作为 suppressed 保留。重复 close 不会重复释放。

`build()` 失败时，assembly 会先逆序关闭已经创建的对象，再把构建失败抛给调用方。调用方注册的 `instance` 不会被关闭。清理失败不会替换真正的构建失败，而是附加到失败上。

最容易混淆的是“factory 创建的参与者”：

- orchestrator 拥有 `initialize`、`start`、`stop`、`abort` 这些生命周期动作。
- assembly 拥有该对象的 `close()`，前提是它实现了 `AutoCloseable`。
- 两者不冲突：先 `stop()`，后 `close()`。

而 `instance` 注册的参与者即使同时实现 `AutoCloseable`，assembly 也不会关闭它。若应用没有另行释放，它就仍由调用方负责；`stop()` 本身不等于调用 `close()`。

## 11. 启动顺序由应用决定，生命周期仍由 orchestrator 决定

推荐顺序：

```text
1. 读取 Configuration
2. 解析 RuntimeConfig / 组件参数
3. 探测 RuntimeCapabilities
4. ServiceAssembly.build()
5. 从 assembly 取出已经构造好的参与者
6. LifecycleOrchestrator.build()
7. LifecycleOrchestrator.start()
```

`build()` 不启动对象，`register(...)` 也不启动对象。orchestrator 的 `start()` 先对所有参与者执行 `initialize()`，再对所有参与者执行 `start()`。注册顺序是启动顺序，逆序是停止顺序。

下面是从 `AssemblyToLifecycleTest` 与 `ApplicationAssemblyBoundaryTest` 改写的衔接形状。`Cache`、`SearchIndex` 是应用自己的生命周期参与者：

```java
RuntimeConfig runtimeConfig = RuntimeConfig.from(configuration);
ThreadPolicy threadPolicy = runtimeConfig.threads();

ServiceAssembly assembly = ServiceAssembly.builder()
        .factory(Cache.class, services -> new Cache())
        .factory(SearchIndex.class, services ->
                new SearchIndex(services.require(Cache.class)))
        .build();

try {
    LifecycleOrchestrator app = LifecycleOrchestrator.builder()
            .threadPolicy(threadPolicy)
            .runtimeCapabilities(RuntimeCapabilities.detect())
            .register("cache", assembly.require(Cache.class))
            .register("index", assembly.require(SearchIndex.class))
            .build();
    try {
        app.start();
        // 应用在这里进入自己的服务循环；它不是 ServiceAssembly 或 orchestrator 的一部分。
    } finally {
        app.stop();
    }
} finally {
    assembly.close();
}
```

这里刻意没有把两个对象放进同一个 try-with-resources 头里，因为那会按声明逆序关闭，可能先关闭 assembly。正常关闭顺序必须是：

```text
app.stop()  ->  assembly.close()
```

参与者停止时可能仍在使用 assembly 创建的连接池或其他基础设施，所以不能反过来。

失败路径同样按所有权拆分：

| 失败位置 | 自动发生的清理 | 应用仍需做什么 |
| --- | --- | --- |
| 配置读取或转换 | 无对象装配 | 修复配置后重新启动 |
| `assembly.build()` | 逆序关闭 assembly 已创建对象 | 不需要关闭一个未返回的 assembly |
| orchestrator `build()` | 尚未启动参与者 | 关闭已经持有的 assembly |
| `initialize()` | 失败参与者执行 `abort()`，已进入参与者逆序 `stop()` | 随后关闭 assembly |
| `start()` | 已进入参与者逆序 `stop()` | 随后关闭 assembly |

orchestrator 启动失败后状态为 `FAILED`，原启动异常放在 `StartupException` 的 cause 中，回滚失败附加在报告里。`stop()` 本身不抛异常，返回 `ShutdownReport`；在 `NEW`、`STOPPED`、`FAILED` 状态调用也有明确语义。默认注册 JVM shutdown hook，宿主容器已经负责进程生命周期时可以关闭它，避免两个机制竞争停止顺序。

## 12. 当前控制台应用采用手工装配，而不是强制使用 ServiceAssembly

当前生产代码 `Console` 没有调用 `Configuration.standard` 或 `ServiceAssembly.builder`。它在 `wiring(DataSource)` 中直接创建 `Database`、`UserRepository`、`SessionStore`、`AuthPlugin` 等对象，再由 `start(List<Plugin>)` 注册到 `LifecycleOrchestrator`。

这并不违反 ADR-0015，反而说明它的含义：应用装配是应用自己的职责，`ServiceAssembly` 是可用工具，不是每个应用必须经过的入口。控制台把端口作为命令行参数，也明确避免为了演示而把该配置塞进全局属性。

选择 `ServiceAssembly` 的理由是对象图较大、希望通过注册代码统一看到构造关系，并且愿意接受急切构建。对象很少时，像 `Console.wiring(...)` 一样直接写构造函数可能更清楚。无论选哪种方式，都不要在框架内部偷偷增加第二个装配机制。

## 13. 不这样设计的代价

- 让组件直接读取环境变量：测试必须修改进程全局状态，同一组件的不同部署难以复现。
- 把 `Configuration` 注入所有组件：依赖方向变成“组件随时回查全局设置”，参数契约变得模糊。
- 把 `Configuration` 传给 orchestrator：配置解析和生命周期耦合，ADR-0015 明确取消了这个便利入口。
- 把坏值静默转换为默认值：拼写错误会表现为一套看似合法但无人要求的配置。
- 用扫描和反射选择构造器：对象图无法从应用代码直接读出，启动错误也更难定位。
- 让 factory 创建参与者并直接调用 `start()`：生命周期被拆成两个引擎，停止顺序和失败回滚不再有唯一所有者。
- 让 assembly 关闭 `instance`：调用方失去所有权，常见结果是泄漏或双重释放。
- 先关闭 assembly 再停止参与者：参与者的清理动作可能使用已经被释放的池或连接。

这些不是理论上的洁癖。当前架构测试直接禁止 core 在 config 包之外读取环境/属性表，禁止 DI 依赖 kernel、concurrent、config、runtime，禁止扫描和反射，并禁止 core 的其他包持有 `Services` 或 `ServiceAssembly`。

## 14. 如何验证

在仓库根目录运行 core 的配置、装配与衔接测试：

```bash
mvn -pl framework/barezen-core \
  -Dtest=ConfigurationTest,ConfigurationPrecedenceTest,StandardConfigurationTest,ConfigurationToThreadsTest,ServiceAssemblyTest,AssemblyToLifecycleTest,ApplicationAssemblyBoundaryTest,ThreadProvidersTest,PlatformThreadProviderConformanceTest,VirtualThreadProviderConformanceTest,BarezenCoreArchitectureTest \
  test
```

其中：

- `ConfigurationPrecedenceTest` 验证逐键覆盖和 later-wins。
- `StandardConfigurationTest` 用真实文件、环境与系统属性验证标准入口。
- `ConfigurationToThreadsTest` 验证从值到 provider 的完整链路。
- `ServiceAssemblyTest` 验证急切构建、循环、失败清理和所有权。
- `AssemblyToLifecycleTest` 验证“装配对象”与“驱动生命周期”是两个动作。
- `ApplicationAssemblyBoundaryTest` 验证启动、停止、失败和重复释放顺序。
- `BarezenCoreArchitectureTest` 用依赖规则防止边界重新耦合。

运行期线程验证应分别在 JDK 17 与 JDK 21+ 进行。JDK 17 上 `AUTO` 应得到 platform provider；JDK 21+ 上 `AUTO` 应得到 virtual provider。显式 `VIRTUAL` 在 JDK 17 必须失败，不能把降级当作测试通过。

## 15. 装配检查清单

- [ ] 每个键都有名称、类型和默认值，并且只在一处声明。
- [ ] 应用清楚 `Configuration.of(...)` 的 later-wins 顺序。
- [ ] 标准部署清楚 file、environment、system properties 的优先级。
- [ ] 环境变量名使用点转下划线并大写的真实规则。
- [ ] 不使用 primitive class 作为 `ConfigurationKey` 类型。
- [ ] 坏值会失败，不依赖错误值回退到默认值。
- [ ] 组件接收已经解析的参数，不持有 `Configuration`。
- [ ] `VIRTUAL` 是不可满足时失败的策略，不是“尽量虚拟”。
- [ ] DI 图通过构造函数和 factory 可读，没有 service locator。
- [ ] `instance` 与 `factory` 的关闭责任已经明确。
- [ ] 对象先构建，参与者后注册，最后才 `start()`。
- [ ] 停止顺序是 orchestrator 先、assembly 后。
- [ ] 启动失败后仍关闭 assembly，构建失败则不重复关闭未返回对象。
- [ ] 工厂创建的参与者没有在 factory 内自行启动或停止。

## 相关源码

- [Configuration.java](../../framework/barezen-core/src/main/java/com/barezen/core/config/Configuration.java)
- [ConfigurationKey.java](../../framework/barezen-core/src/main/java/com/barezen/core/config/ConfigurationKey.java)
- [MergedConfiguration.java](../../framework/barezen-core/src/main/java/com/barezen/core/config/MergedConfiguration.java)
- [ServiceAssembly.java](../../framework/barezen-core/src/main/java/com/barezen/core/di/ServiceAssembly.java)
- [RuntimeConfig.java](../../framework/barezen-core/src/main/java/com/barezen/core/concurrent/RuntimeConfig.java)
- [RuntimeCapabilities.java](../../framework/barezen-core/src/main/java/com/barezen/core/runtime/RuntimeCapabilities.java)
- [ThreadProviders.java](../../framework/barezen-core/src/main/java/com/barezen/core/concurrent/ThreadProviders.java)
- [LifecycleOrchestrator.java](../../framework/barezen-core/src/main/java/com/barezen/core/kernel/LifecycleOrchestrator.java)
- [Console.java](../../app/src/main/java/com/barezen/console/Console.java)
