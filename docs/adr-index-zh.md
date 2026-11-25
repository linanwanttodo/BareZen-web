# BareZen 架构决策中文索引

本页收录 `docs/adr/` 中编号 **0001–0057** 的 57 篇已接受决策，按编号排列；每条的原文链接指向不可改写的英文记录。这里的摘要分三句说明决策、理由或机制、后果或边界；后续记录明确取代或细化早期决策时，索引同时指出关系。英文原文目录见 [ADR README](adr/README.md)，当前实现与验证方式以源码、测试和较新的 ADR 为准，早期记录中的环境与仓库状态只代表写作当时。

## 一、基础约束与运行时

### ADR-0001：内核的唯一第三方编译依赖是 SLF4J API

`barezen-core` 只编译依赖 `org.slf4j:slf4j-api`，具体日志绑定由应用选择。
这样做避免把日志实现及其升级与攻击面强加给所有下游模块，也不重复发明日志门面。
新增任何编译依赖都必须有新 ADR，并由 ArchUnit 与 Maven enforcer 共同拒绝。

[原文](adr/0001-core-third-party-dependency-is-slf4j-api-only.md)

### ADR-0002：JDK 17 是编译基线，新版能力在运行期探测

所有模块统一使用 `maven.compiler.release=17`，JDK 21 的虚拟线程通过运行期能力选择，而不是拆成不同 JDK 模块。
单构件配合 `--release 17` 和一处受约束的探测，比按 JDK 分叉源码或多版本 JAR 更小。
JDK 21+ 可以选择虚拟线程，JDK 17 继续使用平台线程，不能把项目简化称为某个 JDK 版本专属项目。

[原文](adr/0002-jdk-17-baseline-with-runtime-capability-instead-of-jdk-modules.md)

### ADR-0003：启动先初始化全部参与者，再启动全部参与者

`LifecycleOrchestrator.start()` 按注册顺序执行全部 `initialize()`，然后按同一顺序执行全部 `start()`。
初始化阶段只分配和注册资源，启动阶段才绑定端口或接受工作，避免服务器先于依赖就绪。
代价是启动不能并行，且关闭顺序为注册顺序的反向。

[原文](adr/0003-global-two-phase-startup.md)

### ADR-0004：启动失败自行回滚，并保留原始失败

初始化失败的参与者调用 `abort()`，已经完成初始化的参与者按反向顺序调用 `stop()`。
回滚失败只作为 suppressed exception 附加，不能覆盖真正导致启动失败的 `StartupException`。
启动后的编排器进入不可重试的 `FAILED` 状态，调用方无需再手工清理已进入的参与者。

[原文](adr/0004-startup-rollback-preserves-the-original-failure.md)

### ADR-0005：关闭是尽力而为，并把结果作为报告返回

`stop()` 捕获每个参与者的 `RuntimeException` 或 `Error`，继续关闭其余参与者，最后返回缓存的 `ShutdownReport`。
一个组件失败不能让其他组件泄漏，也不能用关闭异常遮蔽正在传播的启动异常。
`close()` 与 `stop()` 一样不抛异常，因此调用方必须读取报告或日志才能发现关闭问题。

[原文](adr/0005-shutdown-is-best-effort-and-reported.md)

### ADR-0006：插件上下文不能成为服务定位器

早期设计中的 `PluginContext` 禁止 `require()`、`get()`、`find()` 等查找能力，协作者只能经构造函数传入。
其禁止方向保留，但提议的六个成员后来被 ADR-0016 整体取代。
原因是其中一些违反配置与生命周期边界，另一些没有真实消费者。

[原文](adr/0006-plugin-context-is-not-a-service-locator.md)

### ADR-0007：请求上下文属于 HTTP 契约层，不属于内核

请求状态应位于 `barezen-web`，核心只保留传输无关的传播原语，且没有消费者时不提前实现。
否则后台任务可能误读当前请求，核心依赖图也会反向依赖 HTTP 层。
该记录提出的请求上下文存在形式后来由 ADR-0018 和 ADR-0020 细化，参数最终显式传给 Handler。

[原文](adr/0007-request-context-belongs-to-barezen-web.md)

### ADR-0008：没有模块可聚合时，不创建聚合 POM

空的 `barezen-runtime` 被删除，只有确实构建产物或聚合模块的目录才成为 Maven 模块。
设计图里的目录只是预测，不应为了对齐图表产生没有内容的模块。
以后恢复聚合模块必须明确列出它实际组织的模块。

[原文](adr/0008-no-aggregator-pom-without-modules.md)

### ADR-0009：生命周期异常先作为运行期异常，再接入统一错误体系

最初只引入 `LifecycleException`、`StartupException` 和 `ThreadCapabilityUnavailableException`，均继承 `RuntimeException`。
这使生命周期接口不必声明受检异常，也让编排器能够区分生命周期失败和普通编程错误。
后续统一错误体系只调整继承根，不改变这些调用契约。

[原文](adr/0009-lifecycle-exceptions-are-runtime-exceptions-pending-the-2-8-taxonomy.md)

### ADR-0010：运行期能力是快照，新版 JDK 探测只允许一处

`RuntimeCapabilities` 只保存 `javaFeature`、`virtualThreads` 和 `vendor`，不是可扩展的特性注册表。
`VirtualThreadProbe` 是唯一执行新版 JDK `MethodHandle` 探测的类，探测结果与执行器创建保持一致。
只有出现真实分支和双 JDK 测试后才能增加能力字段，`ScopedValue` 等没有消费者的能力仍被禁止。

[原文](adr/0010-runtime-capability-seam-scope.md)

### ADR-0011：线程提供器是带所有权关系的执行器工厂

`ThreadProvider` 只创建有界或无界执行器，并关闭由自己创建的资源。
每个子系统拥有独立提供器，避免关闭 HTTP 线程池时误关事件总线，也避免形成全局线程池注册表。
`AUTO` 可以回退到平台线程，显式要求 `VIRTUAL` 时能力不足则启动失败。

[原文](adr/0011-thread-provider-is-a-factory-with-ownership.md)

### ADR-0012：尚未启动也可以关闭，且关闭必须幂等

`stop()` 在 `NEW` 状态下直接进入停止流程，不调用参与者，但仍关闭已创建的线程提供器。
这让 try-with-resources 在启动失败时也能安全清理，不会二次异常遮蔽原始失败。
停止后的编排器不能重启，重新运行必须重新构建。

[原文](adr/0012-new-to-stopped-is-legal-and-idempotent.md)

## 二、配置、装配与核心能力

### ADR-0013：配置在装配时解析一次，并以类型化值传递

`Configuration` 是不可变快照，优先级为键默认值、properties 文件、环境变量、系统属性。
组件在构造时获得已经解析的值，不在运行期重新读取环境或配置中心。
该决策第 6 项关于编排器持有配置的入口后来被 ADR-0015 明确取代，快照与显式解析原则保留。

[原文](adr/0013-configuration-is-resolved-at-assembly-time.md)

### ADR-0014：依赖注入是显式工厂注册，不是反射发现

`ServiceAssembly` 通过 `instance(...)` 和 `factory(...)` 声明构件，工厂正文直接写 `new Impl(...)`。
没有扫描、注解、qualifier 或作用域，`build()` 会急切创建全部对象并在装配时暴露循环或缺失依赖。
工厂创建的对象由 assembly 关闭，外部传入的对象由调用方关闭，DI 也不替代生命周期引擎。

[原文](adr/0014-dependency-injection-is-explicit-factory-registration.md)

### ADR-0015：应用装配是应用自己的职责

配置、能力探测、DI 构建和生命周期编排由应用按显式顺序组合，不新增 `ApplicationContext` 之类的框架类型。
构建失败由 assembly 清理，启动失败由 orchestrator 回滚，随后应用关闭 assembly。
该记录取代 ADR-0013 第 6 项，删除编排器上的 `configuration(...)` 和 `threadKind()` 入口。

[原文](adr/0015-application-assembly-is-a-responsibility-not-a-component.md)

### ADR-0016：插件是能标识自己的生命周期参与者

`Plugin` 继承 `LifecycleParticipant` 并新增 `descriptor()`，协作者来自构造函数，没有第二套生命周期或服务上下文。
插件不能拥有注册表、管理器或查找入口，依赖验证与扩展机制要等真实消费者出现。
该记录细化 ADR-0006，并分别被 ADR-0041 补齐依赖验证、ADR-0047 补齐扩展点、ADR-0048 取代“禁止发现”的结论。

[原文](adr/0016-a-plugin-is-a-participant-not-a-container.md)

### ADR-0017：事件总线是同步能力，不是上下文

`EventBus` 提供 `subscribe`、`publish` 和可取消的 `Subscription`，事件不要求实现标记接口。
分发按精确运行期类型、注册顺序同步执行，首个处理者异常原样传播并终止本次分发。
总线没有线程、生命周期或关闭方法，也不承担插件查找；递归事件循环等成本由调用方承担。

[原文](adr/0017-the-event-bus-is-a-capability-not-a-context.md)

## 三、HTTP 契约、路由与适配器

### ADR-0018：HTTP 是契约，不是服务器

请求、响应、头、方法和处理器等契约类型不可变，且与具体服务器实现分离。
响应返回完整 `byte[]` 而不是流式写入，换取 Handler 不依赖 socket、连接和后返回资源的生命周期。
早期类型的精确集合和单参数 Handler 已被 ADR-0019、ADR-0020 等修订，零编译依赖与服务器无关原则保留。

[原文](adr/0018-http-is-a-contract-not-a-server.md)

### ADR-0019：路由器只回答方法与原始路径的匹配问题

路由按 `/` 分割原始路径，要求段数精确，不解码、不归一化，字面量区分大小写且尾斜杠有意义。
`{name}` 只能占完整一段并捕获一个非空原始段，重复的 method 加 shape 在构建时拒绝。
`Router` 不调用 Handler，也不生成 404 或 405，匹配结果由适配器处理。

[原文](adr/0019-router-semantics.md)

### ADR-0020：路由参数作为 Handler 的第二个参数传递

Handler 最终签名为 `Response handle(Request request, Map<String, String> parameters)`，旧单参数形式被删除。
参数保持原始字符串并独立于 `Request`，避免把路由状态塞进请求属性包。
`RouteMatch` 仍在内部同时持有处理器与参数，由适配器拆分调用。

[原文](adr/0020-route-parameters-contract.md)

### ADR-0021：服务器是独立适配器，不是契约内部的一层

首个实现把 `com.sun.net.httpserver` 限制在 `barezen-http-jdk` 模块，并通过公共接口暴露服务器能力。
契约与核心不依赖该模块，应用装配时选择适配器，真实 socket 测试负责验证边界。
该记录关于适配器同时是生命周期参与者的早期设想被 ADR-0024 拒绝。

[原文](adr/0021-jdk-http-server-adapter.md)

### ADR-0022：JDK 交换对象到契约的桥接忠于原始报文

请求路径和查询分别读取 `getRawPath()` 与 `getRawQuery()`，空查询与无查询保持区别。
响应按已知长度 framing，重复头按值逐行写出，JDK 会规范化头名并丢失全局到达顺序。
HEAD 长度缺口后来由 ADR-0027 关闭，请求体无上限的缺口由 ADR-0031 解决。

[原文](adr/0022-exchange-request-response-bridge.md)

### ADR-0023：HTTP 错误由适配器写成无泄漏的响应

JDK 适配器负责 400、404、405 和 500，404 不泄露路径，405 提供稳定顺序的 `Allow`。
Handler 的 `RuntimeException` 记录服务端日志，但响应只给固定状态文本，不给异常消息或堆栈。
Handler 主动返回的状态不被替换，`OPTIONS *` 的旧 design debt 标签后来被 ADR-0026 取代。

[原文](adr/0023-http-error-semantics.md)

### ADR-0024：一个适配器实例对应一个不可重启的服务器生命周期

适配器按 `NEW → RUNNING → STOPPED` 运行，绑定失败会消耗该实例，停止后必须重新构建。
适配器不是 `LifecycleParticipant`，应用通过参与者组合调用它；自建线程提供器归适配器关闭，借入的提供器只使用不关闭。
停止调用 JDK 的有界 `stop(3)`，保持幂等并保留绑定失败的底层原因。

[原文](adr/0024-server-lifecycle-and-executor-ownership.md)

### ADR-0025：适配器提供器只提供标识和创建，不负责发现

`HttpServerAdapterProvider` 只有 `id()` 和 `create(host, port, router)`，JDK 提供器标识为 `jdk`。
框架不自动调用 `ServiceLoader`，应用在装配阶段比较标识并选择提供器。
提供器不保存服务器、不注册、不查找，也不启动实例。

[原文](adr/0025-http-adapter-provider.md)

### ADR-0026：`OPTIONS *` 的非支持是 JDK 边界事实

目标为 `*` 的请求在进入 BareZen 上下文前就被 JDK 处理，其他方法配合 `*` 目标也受同一限制。
这不是需要补注册的 `OPTIONS` 缺陷，也不是可通过新增路由修复的设计债。
该记录取代 ADR-0023 的 design debt 标签，并用 socket 测试固定这一适配器边界。

[原文](adr/0026-options-star-semantics.md)

### ADR-0027：响应体保持完整字节，framing 头归写出者所有

`Response(status, Headers, byte[])` 不变，1xx、204 和 304 在构造时拒绝非空 body。
Handler 不得设置 `Content-Length` 或 `Transfer-Encoding`，HEAD 则声明 representation 长度但不发送字节。
304 长度问题后来由 ADR-0029 决定，小写 `head` 的长度错误后来由 ADR-0028 取代。

[原文](adr/0027-http-response-body-semantics.md)

### ADR-0028：方法大小写匹配与 HEAD 线上行为是两个边界

九个标准 `HttpMethod` 忽略大小写，因此 `head` 也能找到 HEAD 路由。
只有原始 token 恰好为 `HEAD` 时适配器才抑制 body，其他大小写按普通响应发送真实字节和长度。
该记录取代 ADR-0027 的 limitation 2，同时拒绝为这一个判断新增 `Request.rawMethod()`。

[原文](adr/0028-method-case-and-head-wire-boundary.md)

### ADR-0029：响应 framing 与 304 长度由一张表固定

200 按 body 是否为空声明长度，204 和 304 不声明长度，HEAD 声明 representation 长度但不发送 body。
重复 framing 头即使值相同也拒绝，304 不发送 `Content-Length` 是因为契约没有可验证的“未发送 body 长度”通道。
该记录决定 ADR-0027 的 limitation 1，并把条件 GET 消费者出现作为重新讨论 304 长度的触发条件。

[原文](adr/0029-response-framing-and-304-length.md)

### ADR-0030：响应体继续使用 `byte[]`，流式 body 设准入门槛

没有真实消费者、没有后返回写入者所有者，且流式设计难以保留 HEAD 长度语义，因此不新增 body 抽象。
大响应会完整缓冲并产生复制成本，这是当前明确接受的限制，不是遗漏。
未来重开必须同时带来真实消费者、线程与连接所有权答案，以及保持 ADR-0029 全表的 framing 设计。

[原文](adr/0030-response-body-abstraction.md)

### ADR-0031：JDK 请求体默认限制为 1 MiB

配置键 `barezen.http.jdk.maxRequestBodyBytes` 在构造时解析，默认 1 MiB，provider 路径也使用安全默认值。
已知 `Content-Length` 时按声明值提前拒绝，未知或 chunked 长度时按实际读取计数，超限返回 413。
该限制不解决客户端声明大 body 后停滞不发的 JDK 请求超时问题。

[原文](adr/0031-request-body-limit.md)

### ADR-0032：字节码 major 是构建契约，不是只检查 POM

每个产出模块的 `ClassMajorContractTest` 检查自身 class 文件，仓库级脚本再检查全部 `target/classes` 与 `target/test-classes`。
声明 `release=17` 仍可能被命令行覆盖，所以必须直接读取产物字节并拒绝 major 不是 61 的文件。
零检查数、范围外 class 文件和脚本与测试常量不一致都必须失败，防止验证因看错目录而空转通过。

[原文](adr/0032-class-major-build-contract.md)

## 四、安全、数据与事务

### ADR-0033：安全是 Handler 装饰器，不是请求上下文机制

`SecurityMiddleware.wrap(...)` 固定执行认证、授权，再调用业务 Handler。
需要身份的业务处理器实现 `SecuredHandler`，通过显式参数获得 `SecurityContext`，不使用请求属性或 `ThreadLocal`。
该模块只编译依赖 `barezen-web`，安全策略仍在应用装配处组合。

[原文](adr/0033-security-is-a-handler-decorator.md)

### ADR-0034：认证结果必须区分三种状态

sealed `AuthenticationResult` 区分 `Authenticated`、`Failure` 和 `Anonymous`。
布尔值或可空 principal 会丢失“没有提供凭据”和“提供了错误凭据”的区别，失败原因只留在服务端。
密封结果让新增状态时所有消费者都必须重新检查分支，失败原因不得进入线上响应。

[原文](adr/0034-authentication-has-three-states.md)

### ADR-0035：密码使用 PBKDF2-HMAC-SHA256

默认进行 600,000 轮迭代，使用 16 字节随机盐和 32 字节派生密钥，哈希自带算法名与迭代次数。
格式可描述自身，因此将来提高迭代次数不会让旧哈希失效，比较使用 `MessageDigest.isEqual`。
零依赖约束下没有选择 Argon2 或 bcrypt，且 PBKDF2 对 GPU 与 ASIC 的抵抗较弱，这一代价被明确保留。

[原文](adr/0035-password-hashing-pbkdf2.md)

### ADR-0036：会话是无签名、可撤销的服务端记录

会话 token 由 32 字节安全随机数生成并 Base64url 编码，服务端保存状态以支持撤销和轮换。
领域对象不知道 HTTP 头或 cookie，时钟和随机源可以注入，便于测试过期行为。
会话不跨 JVM 重启或多实例共享。过期记录在查找或轮换时删除，`rotate()` 不会续期过期 token；`size()` 仍不等于有效会话数。

[原文](adr/0036-sessions-are-opaque-and-revocable.md)

### ADR-0037：默认拒绝，并区分 401 与 403

缺少凭据或凭据失败返回 401，已有身份但授权不足返回 403，安全组件异常返回 500。
所有拒绝路径都不调用业务 Handler，包装器每请求只询问一个权限，默认权限为 `Permission.ANY`。
没有单独的错误 wire 区分、`WWW-Authenticate` 或限流，未包装的路由才由应用显式作为公开路由。

[原文](adr/0037-status-codes-and-default-deny.md)

### ADR-0038：数据层只经 `DataSource` 接触 JDBC

`Database(DataSource)` 是唯一入口，驱动、连接池和连接创建属于应用职责。
`SQLException` 统一翻译为 `DataAccessException`，应用回调自己的运行期异常原样传播。
主产物零编译依赖且不使用 `ThreadLocal`，`RowMapper` 只处理单行，不提供 cursor 或流式读取。

[原文](adr/0038-jdbc-boundary-and-driver-free-data-layer.md)

### ADR-0039：事务由回调接收，并由 `Database` 决定提交或回滚

`Transaction` 经回调参数显式传递，`commit` 与 `rollback` 为包私有，repository 无法自行决定事务命运。
正常返回提交，异常或错误回滚，提交失败先尝试回滚，所有路径都关闭连接并尽力恢复 autoCommit。
嵌套事务会开启第二个连接和独立事务，没有 savepoint 或自动加入外层事务。

[原文](adr/0039-transaction-ownership-and-package-private-deciders.md)

### ADR-0040：迁移历史是权威，但不承诺 DDL 可回滚

历史表只记录 `apply()` 成功完成的版本，迁移按版本升序执行并拒绝非法或乱序的待执行项。
H2 实测表明 DDL 可能隐式提交，因此失败后不能声称 schema 已回滚，只能依赖幂等迁移重跑与修复。
没有 down、checksum、自动发现或并发迁移锁，作者必须保证迁移可安全重试。

[原文](adr/0040-migration-history-authority-and-measured-ddl-rollback.md)

## 五、插件、OpenAPI 与测试工具

### ADR-0041：插件注册校验是无状态纯函数

`PluginRegistration.validate(List<Plugin>)` 拒绝重复 id、缺失依赖、循环和 null，并返回满足依赖顺序的列表。
无关插件保留调用方原有相对顺序，校验过程不持有实例、不运行生命周期、不提供查找。
该记录补齐 ADR-0016 留下的验证缺口，同时继续拒绝 registry、resolver 和第二生命周期。

[原文](adr/0041-plugin-registration-is-a-pure-verifier.md)

### ADR-0042：插件位于框架之上，框架不得知道插件存在

AuthPlugin 与 UserPlugin 实现既有安全端口，OpenApiPlugin 当时只收集声明的 `OperationMetadata`。
`PasswordVerifier` 暂留插件包内，认证与签发分离，避免密码进入单值 bearer `Credential`。
七条 ArchUnit 规则固定依赖方向与隔离，OpenAPI 文档生成后来由 ADR-0043 补齐，路由贡献与发现分别由 ADR-0047 和 ADR-0048 扩展。

[原文](adr/0042-plugins-sit-on-the-framework-never-in-it.md)

### ADR-0043：OpenAPI 是声明数据的单向投影

生成器留在插件命名空间，schema 由应用声明，不反射 DTO，输出限定为 OpenAPI 3.0.3 JSON。
`$ref` 只保存名称，因而不需要递归展开，属性顺序由声明决定，确定性集中在 `ApiDocument`。
YAML、完整 3.0.3 语法、扫描和 HTML 文档 UI 均不在范围内，路由与声明的双写仍由应用负责。

[原文](adr/0043-openapi-generator-is-a-declared-data-projection.md)

### ADR-0044：TestKit 是只向下的叶子测试工具

`TestServer` 包装真实 JDK 适配器并绑定 `127.0.0.1:0`，客户端使用 JDK `HttpClient` 且不跟随重定向。
TestKit 不依赖插件，JSON 断言阅读器保持包私有，`TestDatabase` 只重置应用提供的 `DataSource`。
框架其他模块不得依赖 TestKit，避免生产代码或消费者被迫接触插件与测试基础设施。

[原文](adr/0044-testkit-is-a-downward-only-test-tool.md)

### ADR-0045：示例是只使用公共 API 的叶子模块

七个示例合在一个 reactor 叶子模块中，每个主题一个类，并提供工厂、自验证 `main()` 和 TestKit 测试。
可运行数据库示例由应用侧显式引入 H2，因此不违反“框架不命名驱动”的边界。
`main()` 在 Maven 之外用 `java -cp` 观察，因为离线仓库没有 `exec-maven-plugin`；后续静态内容示例按同一规则加入。

[原文](adr/0045-examples-are-a-leaf-public-api-only.md)

### ADR-0046：发布元数据只能记录已经存在的事实

POM 与 README 不虚构 SCM、许可证、开发者、下载地址或 CI badge，未发布版本保持 `0.1.0-SNAPSHOT`。
依赖审计只声称 Maven enforcer 实际检查的内容，不能把未运行的 NVD 扫描写成“零漏洞”。
该记录的“没有 remote”是当时快照；当前仓库已配置 origin，但仍没有 LICENSE，不能沿用历史状态作为现状。

[原文](adr/0046-release-metadata-only-records-what-exists.md)

### ADR-0047：扩展点由拥有被扩展能力的组件声明

`RouteContributor` 接收 `Router.Builder`，`Router.Builder.contributing(...)` 在 `build()` 前按调用方顺序执行贡献。
插件命名空间只负责筛选贡献者，核心与契约都不知道插件存在，也没有 token、context、registry 或注解扫描。
冲突会使装配失败，贡献路由不会自动获得安全保护，OpenAPI 的双写问题仍未解决。

[原文](adr/0047-an-extension-point-is-declared-by-the-component-that-owns-it.md)

### ADR-0048：插件发现只是搜索，不做任何决策

`PluginDiscovery` 通过 `ServiceLoader` 读取服务配置，每次返回新的不可变列表，不缓存、不运行生命周期、不提供按 id 查找。
可发现插件必须有 public 无参构造，需要 DataSource 等协作者的插件仍由应用手工装配。
该记录取代 ADR-0016 第 7 节的禁止发现结论，但 DI 容器和事件总线仍然禁止扫描。

[原文](adr/0048-plugin-discovery-is-a-search-and-nothing-more.md)

## 六、第二服务器、TLS、静态内容与应用

### ADR-0049：第二服务器是独立 Servlet 集成，不是第二个适配器

`BarezenServlet` 使用 Jakarta Servlet API，容器负责端口和生命周期，因此它不是 `HttpServerAdapter`。
路由表经构造函数或初始化时读取的一个 context attribute 注入，路径使用去掉 context path 的原始 URI。
Servlet API 为 provided scope，Tomcat 只在测试中出现；容器先行拒绝的请求会保留容器自己的错误页差异。

[原文](adr/0049-a-second-server-is-a-servlet-in-its-own-module.md)

### ADR-0050：通过 keystore 配置启用 TLS，而不是增加布尔开关

`barezen.http.jdk.tls.keystorePath` 与 `keystorePassword` 同时存在才启用 TLS，缺密码会在构造时失败。
坏 keystore 在绑定端口前报错，密码不得进入日志或异常消息，TLS 实现保持包私有。
协议、cipher、mTLS、SNI 和 HTTP 转 HTTPS 策略不配置，`Request` 也不提供 scheme 或 `isSecure()`。

[原文](adr/0050-tls-is-named-by-a-keystore-not-flagged-on.md)

### ADR-0051：静态内容是一文件一 Handler

`StaticContent.file(...)` 要求已存在的普通文件，应用为每个文件显式注册 GET 与 HEAD 路由。
ETag 来自内容 SHA-256 的前八字节，304 实测后仍不声明 `Content-Length`，文件按请求完整读入内存。
没有通配路由、目录列表、range、缓存策略或流式大文件，也不会从请求接受文件路径。2026-11-25 补充 `resource(ClassLoader, name)`：显式加载并冻结 classpath 资源，仍保持一资源一路由与相同 ETag/304 规则。

[原文](adr/0051-static-content-is-one-handler-per-file.md)

### ADR-0052：文档站曾是手写文件的叶子模块

该决策选择手写 HTML、一个样式表和测试检查路由与链接，拒绝生成器、模板引擎与构建步骤。
它曾把文档站当作真实消费者，但明确承认没有使用插件，也没有通用前端。
其 server-delivered 交付方式后来被 ADR-0054 取代，无生成器和手写页面的选择保留。

[原文](adr/0052-the-website-is-a-leaf-of-hand-written-files.md)

### ADR-0053：语言是地址的一部分，不做内容协商

英文位于 `/` 与 `/docs/...`，中文位于 `/zh/` 与 `/zh/docs/...`，每页用导航链接和 head alternate 互链。
不接受 `Accept-Language`、cookie 或 JavaScript 切换，避免同一地址返回不同 body 和不可链接的语言状态。
代码 token 不翻译，翻译漂移是接受成本；`/zh` 到 `/zh/` 的重定向后来被 ADR-0054 取代。

[原文](adr/0053-a-language-is-part-of-the-address.md)

### ADR-0054：文档就是静态文件夹，因为部署主机不运行框架代码

`site/` 退出 reactor，所有链接改为相对地址，页面保留 `.html`，由 GitHub Pages 直接提供文件。
`scripts/check-site.sh` 检查磁盘链接、可达性、双语对应和绝对地址，不引入预览服务器。
该记录取代 ADR-0052 的交付方式和 ADR-0053 的重定向，同时明确文档站不等于应用前端。

[原文](adr/0054-documentation-is-a-folder-of-static-files.md)

### ADR-0055：应用叶子通过真实消费者赢得“前端”资格

`app/` 通过登录签发 bearer token，并使用插件贡献报表接口，页面直接显示原始 HTTP 答案。
应用只使用公共 API，自己命名 H2、手写 JSON 处理，并使用表单编码而非假设框架提供 JSON 解析。
该记录后来由 ADR-0057 取代 token 仅存于一个 JavaScript 变量的决定，也部分调整页面宽度规则。

[原文](adr/0055-a-console-application.md)

### ADR-0056：控制台增长为多视图，事件总线获得消费者

七个 hash 视图共享十九个路由，展示轮换、过期、权限、分页和审计事件，不引入客户端框架或构建步骤。
事件总线由应用组件直接订阅，按精确类型同步工作，审计记录有上限且看不到授权拒绝。
授权答案只保留 `UserPlugin` 一个来源；该记录的单文档视图决定后来被 ADR-0057 取代。

[原文](adr/0056-the-console-grows-views.md)

### ADR-0057：七个页面与跨文档会话带来的凭据问题

七个 clean 地址分别对应独立文件，并为 GET 与 HEAD 注册路由，token 和答案日志移入 `sessionStorage`。
CSP 装饰器禁止内联与外部脚本，并叠加 nosniff、frame-ancestors、base-uri 和 no-referrer，作为同源脚本可读取 token 的缓解。
该记录取代 ADR-0055 的 token 存储和宽度条款以及 ADR-0056 的单文档视图，但再次明确非本机部署应采用 HttpOnly cookie 方案。

[原文](adr/0057-seven-pages-and-the-credential-problem.md)
