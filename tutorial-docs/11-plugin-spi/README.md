# 第 11 章：插件不是小容器，而是能说明自己的生命周期参与者

## 本章目标

前十章已经拥有生命周期、HTTP 契约、路由、服务器适配器、配置装配、安全与数据。现在要回答一个更危险的问题：一个陌生人写好的组件，怎样进入应用，而又不让框架变成插件管理器？如果只看到“支持插件”四个字，很容易一路写出扫描器、注册表、上下文、依赖查找和自动启动。等到这些类型出现，插件已经不再是一个普通对象，而是框架里的第二套容器。

BareZen 的选择相反。插件 SPI 位于 `framework/barezen-core` 的 `com.barezen.core.plugin`，`Plugin` 只继承 `LifecycleParticipant` 并增加 `descriptor()`。应用仍然负责构造对象、提供构造参数、登记参与者、启动与停止；orchestrator 仍然只执行一套生命周期。插件描述符只声明 `id` 与依赖 id，`PluginRegistration.validate` 是一个无状态的纯校验与排序函数，不保存插件，也不启动任何对象。

`framework/barezen-plugins` 则是另一层：它发布 auth、user、openapi 等具体插件，组合 security、data 与 web 的既有端口。它不拥有 SPI，也不允许反过来被框架依赖。这个区分是本章第一条结构规则：**核心声明插件是什么，插件模块提供插件是什么，应用决定这次运行包含哪些插件。**

本章还会建造第一个由组件自己声明的扩展点。`barezen-web` 声明 `RouteContributor`，因为它拥有路由；`barezen-plugins` 通过 `RouteContributions.of` 完成 `Plugin` 与 `RouteContributor` 的类型收窄；应用在装配期把贡献路由加入 `Router.Builder` 并构建冻结表。贡献不是新的生命周期阶段，插件也不会因此自动获得安全保护。

最后加入 `PluginDiscovery`。它调用 JDK `ServiceLoader`，每次返回新的不可变列表，不缓存、不选择、不启动。手工构造和自动发现只在 `PluginRegistration.validate(merged)` 汇合，因此跨来源的重复 id、缺失依赖和循环同样会在启动前失败。真实示例将证明：贡献路由的实例与接受生命周期的实例是同一个对象，启动顺序由依赖图决定，坏密码不会创建会话。

## 涉及 ADR

- [ADR-0016：插件是参与者，不是容器](../../docs/adr/0016-a-plugin-is-a-participant-not-a-container.md)：确定插件与 LifecycleParticipant 的关系，拒绝 `PluginContext`、注册表和第二生命周期引擎。其早期“拒绝发现”的结论后来由 ADR-0048 修订，阅读时要结合当前代码。
- [ADR-0041：插件注册校验是纯函数](../../docs/adr/0041-plugin-registration-is-a-pure-verifier.md)：`validate` 只读取描述符、检查图并返回有序副本；应用必须显式调用它。
- [ADR-0042：插件位于框架之上，永远不在框架之内](../../docs/adr/0042-plugins-sit-on-the-framework-never-in-it.md)：具体插件属于 `barezen-plugins`，框架主源码不得依赖该命名空间。
- [ADR-0047：扩展点由拥有它的组件声明](../../docs/adr/0047-an-extension-point-is-declared-by-the-component-that-owns-it.md)：路由扩展点归 web，不在 core 制造一个通用的 `ExtensionPoint<T>` 与 registry。
- [ADR-0048：插件发现只是搜索](../../docs/adr/0048-plugin-discovery-is-a-search-and-nothing-more.md)：`ServiceLoader` 可以找到插件，但选择、装配和生命周期仍属于应用。
- [ADR-0003：两阶段全局启动](../../docs/adr/0003-global-two-phase-startup.md) 与 [ADR-0004：启动失败回滚](../../docs/adr/0004-startup-rollback-preserves-the-original-failure.md)：插件没有另一套 initialize/start/stop 顺序，失败也沿用 orchestrator 的 rollback 规则。
- [ADR-0018：HTTP 是契约，不是服务器](../../docs/adr/0018-http-is-a-contract-not-a-server.md)、[ADR-0019：路由语义](../../docs/adr/0019-router-semantics.md) 与 [ADR-0025：HTTP 适配器 provider](../../docs/adr/0025-http-adapter-provider.md)：用于分清 handler、router、adapter 和 provider。一个 HTTP provider 不是插件，插件贡献路由也不等于拥有 socket。
- [ADR-0006：PluginContext 不是 service locator](../../docs/adr/0006-plugin-context-is-not-a-service-locator.md)、[ADR-0014：显式工厂注册](../../docs/adr/0014-dependency-injection-is-explicit-factory-registration.md) 与 [ADR-0015：应用拥有装配责任](../../docs/adr/0015-application-assembly-is-a-responsibility-not-a-component.md)：解释为什么插件协作者必须来自构造参数，发现结果为什么必须交给应用汇合。

## 本章提交地图

| 提交 | 当前主线上的真实变化 | 阅读重点 |
| --- | --- | --- |
| `780af74e7190e301d8e3c0556e1fe8aa1e4a96d7` | 新增 `Plugin`、`PluginDescriptor`、PluginTest 与 core 架构约束，7 文件、737 行新增 | 插件只比普通参与者多一个自描述方法 |
| `72eaf7063538385ebc8d63d7d08dd4c98ae89ca9` | 新增 `PluginRegistration` 与测试，2 文件、386 行新增 | 校验、缺失依赖、循环与稳定拓扑排序 |
| `68e0c672b6f7610b4840d7f7a1353e6c14655285` | 新增 `barezen-plugins`、auth/user/openapi 七个生产类型与模块/BOM 坐标 | 具体插件通过端口组合，不依赖 sibling 实现 |
| `0971abc64b56f72430f572ffa9536cd0815997c5` | 新增 examples 模块、七个示例及测试 | 第一次把 auth/user/openapi 装配进应用 |
| `d7c7e831b5805afe6bf41c6420ff2dce8701fa83` | web 新增 `RouteContributor`，Router.Builder 增加 `contributing` | 扩展点由路由拥有者声明 |
| `1b52c537df2f9d69f439f72b0a276ed064e7889b` | plugins 新增 `RouteContributions` 与测试 | 只在插件命名空间做接口收窄 |
| `8978333e31e3ede80b68bb4f7ba75ecb9110b45f` | 示例加入贡献路由的插件并扩充测试 | 装配期贡献进入真实示例 |
| `f478c1c38be1757e657cfe8e3756ff5c3f6238fe` | core 新增 `PluginDiscovery`、两个 fixture 与 services 文件 | 发现只是 ServiceLoader 搜索 |
| `517aee7cf856b2ba7cfc1be84eacf0229156575f` | 新增 `ReportsPlugin`，示例汇合手工与发现来源 | 同一实例先贡献路由，再接受生命周期 |

```bash
git show --stat 780af74
git show 72eaf70 -- framework/barezen-core/src/main/java/com/barezen/core/plugin/PluginRegistration.java
git show d7c7e83 -- framework/barezen-web/src/main/java/com/barezen/web/Router.java
git show f478c1c -- framework/barezen-core/src/main/java/com/barezen/core/plugin/PluginDiscovery.java
git show 517aee7 -- examples/src/main/java/com/barezen/examples/plugins/PluginExample.java
```

仓库里还有内容相近的平行分支提交，但它们不是当前 `HEAD` 的祖先。本章只使用 `git log --first-parent HEAD` 能追溯的上述坐标。阅读历史提交时还要注意：ADR 保存的是当时的决定，后续提交会修订它。例如 ADR-0019 曾描述插件在 `initialize()` 中向 builder 注册路由，当前实现已把 `RouteContributor` 的贡献放在装配期；教程以当前源码和 ADR-0047 为准，不把历史示例当成现存 API。

## 步骤导航

1. [插件是参与者，而不是容器](01-插件是参与者而不是容器.md)：读最小 SPI、描述符和生命周期归属，先阻止 manager/context 的出现。
2. [依赖声明与纯校验汇合](02-依赖声明与纯校验汇合.md)：用 `validate` 检查重复、缺失和循环，理解稳定拓扑顺序及其调用边界。
3. [首批插件与端口隔离](03-首批插件与端口隔离.md)：组合 auth/user/openapi，观察构造参数、内部端口与 sibling 隔离。
4. [路由扩展点与装配期贡献](04-路由扩展点与装配期贡献.md)：由 web 声明贡献接口，在 build 前冻结路由，并验证冲突拒绝。
5. [发现机制与真实示例检查点](05-发现机制与真实示例检查点.md)：用 ServiceLoader 找无依赖插件，与手工列表汇合，运行模块测试和真实 main。

## 本章完成后的能力清单

你应能写出一个只依赖构造参数、实现 `Plugin` 的类，并用 `descriptor().id()` 注册到现有 orchestrator。你应能解释插件为什么没有 `install`、`uninstall`、`PluginContext`、`require` 或自己的状态机；为什么 SPI 在 core 而具体插件在 plugins；为什么应用必须保留对象引用并调用 `stop()`。

你应能把 `[auth, user, openapi, reports]` 校验为 `[user, openapi, auth, reports]`，说明依赖只约束先后，不负责注入协作者。你应能让重复 id、缺失 id、自依赖、空描述和循环在数据库或 socket 启动前失败，同时知道 `validate` 返回新列表而不是修改调用者列表，框架也不会偷偷替你调用它。

你应能让 `AuthPlugin` 通过 `PasswordVerifier` 使用 `UserPlugin`，而不引用 sibling 具体类；知道 session 验证与密码验证是两个问题；知道 `OpenApiPlugin` 当前只是 metadata collector，并不读取 Router 或生成文档。你应能把 SQL 留在 user repository，把 schema 创建放进 `UserPlugin.initialize()` 的一个事务，并理解重启幂等依赖 migration 自己写好。

你应能在装配期调用 `RouteContributions.of(plugins)`，把结果交给 `Router.Builder.contributing`，然后在 `build()` 后把冻结表交给 adapter。你应能预测方法加规范化形状重复时的 `IllegalArgumentException`，并明确贡献路由不会自动套上 security middleware。

你应能解释 `PluginDiscovery.discover()` 的无参与指定 ClassLoader 重载、fresh immutable list、public no-arg provider 限制、`ServiceConfigurationError` 传播和无缓存行为。你应能合并 discovered 与 hand-built 列表，并证明发现不选择、不启动、不持有容器。

## 不这样设计会坏掉什么

若插件管理器替应用保存插件、查找协作者并自动启动，启动顺序就从应用代码搬到了框架全局状态。测试之间会共享实例，关闭顺序无人知晓，一个坏 provider 可能阻止整套应用组装。若给每个插件一个上下文，`PluginContext.require(X.class)` 会再次制造 service locator，构造参数里的依赖图也随之失去意义。

若 `PluginDescriptor` 允许可选依赖却被校验器忽略，缺失依赖会推迟到运行期；若重复 id 采用 last-writer-wins，两个 jar 会静默覆盖。若拓扑排序按字母重排，应用原本可读的独立插件顺序会被打乱；若图有环却只返回“排不完”，维护者无法知道该修哪一组声明。

若 auth 直接 import user 的 `UserPlugin`，两个 sibling 的具体实现就绑在一起，替换用户存储也要改 auth。若密码验证在 authenticate 时重复执行， bearer token 的每次请求都会付出密码哈希成本；若未知用户和错误密码返回不同信息，又会制造账号枚举线索。若 SQL 进入 security 模块，安全端口就会依赖具体表结构。

若路由扩展点放在 core，web 无法依赖 core 的 Plugin，core 却会为了一个 HTTP 能力提前知道路由。若在 `start()` 中动态注册路由，adapter 可能已经读取了旧表；若 `Router.Builder` 不复制注册列表，后续注册会悄悄改变运行中的表。若贡献者重复注册时后者覆盖前者，两个插件的冲突就变成加载顺序抽奖。

若发现机制缓存单例、自动选择“第一个”或吞掉 `ServiceConfigurationError`，应用就失去了对本次运行的明确控制。若要求所有插件都能发现，Auth/User 这类需要 DataSource、SessionStore 的插件会被迫使用全局服务来满足 no-arg 构造器。正确做法不是消灭限制，而是保留两条来源，在装配处诚实汇合。

## 下一章预告

第 12 章进入 OpenAPI 文档与 testkit。你会看到 `OpenApiPlugin` 收集的 operation metadata 如何被显式组装成 `ApiDocument`，文档模型如何约束路径、参数、响应、schema 与 security scheme，以及为什么 framework 不自动从 Router 反射出一份看似完整、实际可能撒谎的文档。随后用 testkit 对真实 HTTP 行为做黑盒断言，而不是只验证对象在内存里“看起来正确”。
