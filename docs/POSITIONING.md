# BareZen 的定位：学习资产与轻量服务骨架

## 一句话定位

BareZen 是一个**用于学习 Web 框架构造过程、也可以承载小型同步服务的轻量 Java 框架**。它把 HTTP 契约、路由、生命周期、显式装配、安全、数据访问和文档生成拆成可以逐层读懂的模块，并用 ADR、架构测试、真实端口测试和字节码门禁约束这些边界。

它不试图成为 Spring Boot 的替代品，也不以“功能数量”作为主要目标。更准确的使用方式是：**把它当作一份可以运行、可以拆开研究、也可以按需裁剪的工程骨架**。

## 它现在是什么

当前根 POM 的版本是 `0.1.0-SNAPSHOT`。仓库中的框架能力已经超出最小 HTTP 示例，形成九个框架产物：

| 模块 | 实际提供的能力 | 对使用者的意义 |
|:---|:---|:---|
| `barezen-core` | 生命周期、线程提供者、配置、显式工厂装配、事件总线、插件 SPI 与发现 | 不包含 HTTP、SQL 或安全概念的运行骨架 |
| `barezen-web` | `Request`、`Response`、`Headers`、`HttpMethod`、`Handler`、`Router`、适配器接口和路由贡献接口 | 服务器无关的 HTTP 契约 |
| `barezen-http-jdk` | 基于 `com.sun.net.httpserver` 的服务器适配器、TLS、请求体限制与错误响应 | 独立启动一个真实 HTTP 服务 |
| `barezen-http-servlet` | `BarezenServlet`，把同一套路由放进 Jakarta Servlet 容器 | 嵌入已有 Tomcat、Jetty 等部署，而不是替换容器 |
| `barezen-security` | Handler 装饰器、认证与授权端口、PBKDF2 密码哈希、不透明会话 | 在业务 Handler 之外组合认证、授权和会话 |
| `barezen-data` | `Database(DataSource)`、显式事务、版本化迁移 | 使用应用选择的 JDBC 驱动和连接池 |
| `barezen-content` | 单文件静态 Handler、内容类型、ETag、条件 GET 与单范围 GET | 为已知文件注册明确的静态资源路由 |
| `barezen-plugins` | 认证、用户和 OpenAPI 插件实现 | 展示插件如何坐在框架之上，而不是被框架反向依赖 |
| `barezen-testkit` | JDK 适配器上的真实端口测试服务、HTTP 客户端和测试夹具 | 让消费者从公开 API 验证真实 HTTP 行为 |

`examples/` 是仅依赖公开 API 的叶子模块，包含八个可运行主题；`app/` 是控制台示例应用。两者都不是框架运行时必须携带的能力。

这些能力共享几个可以直接观察的约束：

- **同步 Handler。** 最终签名是 `Response handle(Request request, Map<String, String> parameters)`。它返回完整响应，不持有连接，也不在返回后继续写字节。
- **明确装配。** `ServiceAssembly` 通过 `instance` 和 `factory` 注册对象，没有包扫描、反射注入、注解注入或隐式单例发现。
- **插件有边界。** `PluginDiscovery` 使用 JDK `ServiceLoader` 查找公开、无参构造的插件；排序交给 `PluginRegistration`，生命周期交给 `LifecycleOrchestrator`。DI 容器本身仍然不扫描。
- **路由先冻结。** 插件通过 `RouteContributor.contributeRoutes(Router.Builder)` 在 `build()` 前贡献路由，重复 method + shape 会使装配失败。
- **字节码基线是 17。** 父 POM 固定 `maven.compiler.release=17`，产物由 class-major 61 门禁复核。JDK 21 及以上运行期可探测并启用虚拟线程；JDK 17 使用平台线程。这不是“JDK 21 项目”，也不是“只能构建在 JDK 17 上”的项目。

## 为什么称它为学习资产

BareZen 的价值不只在“能启动一个服务”，更在它把常见框架问题拆成可以逐步验证的小问题：

1. HTTP 契约应该包含什么，才能不绑定某个服务器？
2. 路由参数为什么应该独立于客户端请求，而不是塞进请求属性？
3. 原始路径什么时候解码，尾斜杠和大小写意味着什么？
4. 谁决定 404、405、413 和 500，谁负责把异常写进日志？
5. 密码与会话分别解决什么问题，为什么 token 不需要签名也能在单实例内使用？
6. 多语句数据库操作在哪里开启事务，为什么事务对象要显式传给回调？
7. 配置、对象装配和生命周期启动为什么是三个不同阶段？
8. 插件发现、依赖排序、路由贡献和资源关闭分别由谁负责？

每个问题都有对应的真实源码、测试和决策记录。读者可以沿着 `tutorial-docs/` 的建造顺序重新推导，而不是只学习一套 API 调用方式。文档中的示例、测试工具和可运行应用，使这些推导可以落到真实 socket、真实 JDBC 驱动和真实字节码上。

因此，它尤其适合：

- 第一次设计 HTTP 框架或中间件的开发者；
- 想理解服务器适配器、Handler 装饰器、事务所有权和插件装配边界的 Java 学习者；
- 需要一个小型同步 API，并希望代码结构、测试和架构约束都能被团队读懂的内部项目；
- 已有 Servlet 容器，希望复用 BareZen 路由，但不愿把容器生命周期塞进业务代码的团队。

## 轻量具体意味着什么

“轻量”在这里首先是**依赖方向和认知范围**，而不是未经测量的性能结论。

- `barezen-core` 的唯一编译期第三方依赖是 `slf4j-api`，当前由父 POM 固定为 2.0.16。它提供日志门面，不把日志实现塞进每个消费者。
- `barezen-web` 没有编译依赖；`barezen-data` 的生产代码也没有编译依赖，只使用 JDK 的 `javax.sql`。
- `barezen-security` 只依赖 `barezen-web`，不依赖服务器适配器。
- `barezen-http-servlet` 的 Servlet API 是 `provided`，由部署容器提供；Tomcat 只出现在该模块测试中。
- 应用选择数据库驱动、连接池和部署容器。框架不替应用建立一套全局产品选择。
- 父 POM 通过 enforcer 禁止 Spring 等指定编译依赖。不依赖 Spring 是可执行的构建约束，不只是 README 口号。

这种结构允许一个服务只引入自己真正需要的模块。代价是：配置、安全策略、错误表示、连接池、序列化等应用决策，通常仍要由应用显式完成。

## 当前能力边界

以下边界会直接影响选型，不能只用“轻量”两个字略过。

### HTTP 与并发模型

- Handler 是同步的，`Response` 的 body 是完整 `byte[]`。没有响应流、SSE、WebSocket 或异步 Handler 契约。
- 大文件、视频和大型导出会把完整表示放入内存。虚拟线程改变线程执行方式，不会把同步阻塞代码自动变成非阻塞代码。
- 请求体有默认上限；JDK 适配器默认 1 MiB，超过时在读取 Handler 输入前回答 413。
- JDK 适配器拥有自己创建的线程提供者；传入的线程提供者是借用的，不由适配器关闭。两种 provider 的有界 executor 都限制同时执行数，但虚拟实现会在提交线程等待准入槽位，平台实现把超额任务放入委托队列；虚拟 executor 关闭时会唤醒等待者并拒绝新提交。
- Servlet 集成不拥有端口、线程池或生命周期。容器的请求解析和错误页面也可能先于 BareZen 作出响应，因此两种部署方式的错误页面不一定逐字节相同。真实 Tomcat 测试已经为所测状态码、HEAD、字段、body 与 keep-alive 矩阵提供跨适配器证据，但没有覆盖 Jetty/WildFly、HTTP/2、异步 Servlet、multipart 或所有容器协议行为。

### 路由与静态资源

- 路由使用原始路径段：不解码、不归一化、段数必须完全相同、字面量大小写敏感、尾斜杠有意义。
- 没有通配符、前缀路由或多段捕获。`StaticContent.file(...)` 与 `StaticContent.resource(...)` 以“一资源一路由”工作，应用在装配时枚举磁盘文件或显式选择 classpath 资源。
- `HEAD` 不是从 `GET` 自动推导的。需要同时提供 HEAD 时，要分别注册两个方法。
- 静态内容不提供目录列表、多范围响应、压缩或 `Cache-Control` 默认策略；GET 支持单个 `bytes` 范围，畸形或多范围请求退回完整 200，越界范围返回 416。

### 安全与会话

- 密码哈希使用 PBKDF2-HMAC-SHA256，默认 600,000 次迭代、带随机盐；验证拒绝非正或超过 2,000,000 次的损坏记录。
- `SessionStore` 是单实例内存存储。重启会丢失会话，多个实例不会共享它，也没有分布式失效广播。
- 过期记录在查询或轮换时删除；`size()` 不等于“仍有效的会话数”，`rotate` 不会续期过期 token。
- 默认中间件从 `Authorization` 读取 Bearer 凭据；scheme 忽略大小写，token 值精确匹配。重复 `Authorization` 字段在认证前拒绝，避免代理与后端选择不同凭据。没有现成的 Cookie 凭据来源、CSRF 防护、OAuth/OIDC 流程或完整账户系统。`barezen-plugins` 有 H2 用户仓库，但它不是数据库无关的账户管理平台。
- 已认证但无权限是 403；当前中间件把匿名、认证失败、重复字段和无法形成凭据的畸形 `Authorization` 头都回答为 401，安全组件抛出运行时异常时回答 500，但不会自动记录该异常。`AuthPlugin` 保留三态：null credential 为匿名，无效 scheme、未知或过期 token 为 Failure；wire 仍统一返回 401。
- JDK TLS 只在构造时解析；未配置 path 时保持明文，显式空白 path 会失败而不是静默降级。没有证书轮换、mTLS、协议/cipher 配置或 HTTP/2 协商策略。日志脱敏不等于密码已从配置快照或临时字符数组中擦除。

### 数据与配置

- `Database` 不是 ORM，不提供实体映射、懒加载、查询 DSL 或自动连接池。
- 单语句方法各自借用连接；跨语句工作必须使用 `Database.transaction(...)`，事务对象作为回调参数传递，不依赖 `ThreadLocal`。
- 不支持嵌套事务或 savepoint；嵌套调用会打开另一条连接和另一个独立事务。
- 迁移历史是已成功应用的权威记录，但 H2 等数据库的 DDL 可能隐式提交。失败恢复依赖可重复执行的迁移，而不是承诺回滚全部 schema。
- 配置在构造时成为快照，运行期不 reload。值在 `get` 时转换，坏值会抛出 `ConfigurationException`，不会悄悄退回默认值。

### OpenAPI 与序列化

- OpenAPI 生成器输出 OpenAPI 3.0.3 的 JSON 子集，schema 和 operation 由应用声明，不反射扫描 DTO。metadata collector 不是线程安全的，`clear()` 可由调用方修改，也没有自动 Router 一致性校验。
- 它不提供 YAML、Swagger UI 或 ReDoc 页面，也不覆盖完整 3.0.3 语法。
- 框架没有通用 JSON 解析器或模板引擎。应用需要序列化、表单解析或页面渲染时，应自行选择库并放在应用装配层。

## 何时不适合用它

选择 BareZen 之前，应先排除以下需求：

- 需要成熟、广泛的第三方 starter、插件市场和开箱即用的企业集成；
- 需要 OAuth/OIDC、多租户、分布式会话、Cookie 会话与 CSRF 防护组成完整方案；
- 需要响应式非阻塞 I/O、HTTP/2 或 HTTP/3 服务器能力、WebSocket、SSE、流式下载；
- 需要 ORM、复杂查询、分布式事务或由框架统一管理的连接池；
- 需要模板引擎、完整 JSON 绑定、文件上传组件或管理后台生成器；
- 需要明确的长期支持、稳定版本升级承诺、公开安全响应流程或经过验证的依赖漏洞审计；
- 无法接受由应用自己完成部署容器、错误页面、序列化和观测策略等决策。

这些差距不是“稍后再补几个 API”那么简单。它们各自会引入依赖、生命周期、配置和测试责任。BareZen 的选择是先把边界说清楚，而不是用大量便利接口掩盖它们。

## 与 Javalin 的事实性横向参照

Javalin 与 BareZen 都位于轻量 Java Web 路线。根据 Javalin 官方当前 v7 文档，Javalin 要求 Java 17 及以上，基于 Jetty 12，通过 `Javalin.create(config -> ...)` 集中配置路由、插件和服务器选项；它提供 bundled plugins、OpenAPI/Swagger UI/ReDoc 支持，以及启动真实随机端口服务器的 `JavalinTest`，客户端基于 OkHttp。以上外部信息核对日期为 2026-11-24。

| 观察维度 | BareZen 当前仓库 | Javalin v7 官方文档 |
|:---|:---|:---|
| 核心形态 | HTTP 值类型与单方法 Handler 契约，服务器实现分开 | Java/Kotlin Web 框架，路由、Handler 与配置集中在 `Javalin.create(...)` |
| 服务器路线 | JDK `HttpServer` 适配器；另有 Jakarta Servlet 集成，容器拥有生命周期 | Jetty 12，并暴露 Jetty 的 SSL、HTTP3 等配置 |
| 装配方式 | 显式工厂注册；无参插件可由 `ServiceLoader` 发现，但 DI 不扫描 | 集中式创建配置和 bundled plugins |
| 数据访问 | 自带 `DataSource` 边界、显式事务和迁移模块，不绑定驱动 | 本项未作对应能力比较 |
| OpenAPI | 声明式 OpenAPI 3.0.3 JSON 子集，不含文档 UI | 官方文档列出 OpenAPI、Swagger UI 和 ReDoc 支持 |
| 集成测试 | `TestKit` 在 JDK 适配器上绑定 `127.0.0.1:0`，客户端使用 `java.net.http` | `JavalinTest` 启动真实随机端口服务器，返回 OkHttp `Response` |
| 工程约束 | ADR、ArchUnit、依赖方向规则、真实 socket 测试、class-major 61 门禁 | 本文不推断其仓库的架构测试、发布成熟度或工程质量 |

两者最值得区分的，不是一句笼统的“谁更轻”，而是**当前仓库呈现的工程纪律密度**：BareZen 把原始路径、HEAD、304、线程所有权、插件贡献和事务边界都写成显式契约，再用测试和决策记录固定下来。这使源码适合逐层研读，也意味着应用必须理解这些约定。

这不是性能基准，也不是“严格等于更好”的结论。对于希望集中配置、直接获得 Jetty 能力和文档 UI 的开发者，Javalin 的现成路线可能更合适；对于希望亲手拆解框架边界、把每个扩展点与 ADR 和测试对应起来的学习者，BareZen 的源码结构更有阅读价值。未在上表列出的维度不作优劣推断。

## 当前项目状态与采用前的核对

- 根 POM 使用 `0.1.0-SNAPSHOT`；不要把它当作已经承诺兼容性和支持周期的正式发行版。
- 仓库当前已经配置 `origin`。早期 ADR 中“没有 remote”的描述是当时快照，不再代表当前状态。
- 当前仓库没有 `LICENSE` 文件，POM 也没有 `<licenses>` 声明。复制、修改和再分发之前，采用者应自行确认授权；本文不代为选择许可证。
- `.github/workflows/backend-ci.yml` 声明 JDK 17/21 构建矩阵，且已观察到远端运行；最新结论以 GitHub Actions 为准。本文不静态保存 CI 徽章、发布下载地址或漏洞扫描结论，也不把选定测试或行覆盖率扩展为完整发布验收。
- 构建日志中的 JDK 版本只说明该次构建使用哪个编译器。`release=17` 与 class-major 检查说明字节码基线；虚拟线程能力仍取决于实际运行 JDK 的探测结果。
- 此前记录的 JDK 请求行测试差异已由 method token 校验消除，教程不再保留“已知失败”条目；采用时仍应以目标 JDK、目标容器和目标数据库上的复现结果为准，不能把某台构建机的绿色当成所有环境的证明。

## 阅读路径

1. 先读 [HTTP 契约与 Handler](guide/web-handlers.md)，理解请求、响应和路由的边界。
2. 再读 [安全认证与会话](guide/security.md) 和 [数据访问与事务](guide/data.md)，观察 Handler 装饰器和 `DataSource` 如何保持模块独立。
3. 阅读 [配置解析与显式装配](guide/configuration-assembly.md)，把配置、对象创建和生命周期释放分开。
4. 从 [ADR 索引](adr/README.md) 进入具体决策，再按建造教程的顺序重建这些机制。
5. 最后运行 [示例](examples/README.md) 和 [TestKit 快速开始](testkit/quick-start.md)，用真实端口验证行为。

## 外部参考

- [Javalin 官方 v7 文档源文件](https://github.com/javalin/website/blob/master/pages/docs/docs.md)
- [Javalin 官方文档入口与索引](https://github.com/javalin/website/blob/master/llms.txt)
- [BareZen 变更记录](../CHANGELOG.md)
