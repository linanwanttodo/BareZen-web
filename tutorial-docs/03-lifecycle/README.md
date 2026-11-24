# 第 3 章：生命周期、两阶段启动与失败回滚

## 本章目标

第二章保证产物和依赖不漂移，第三章开始回答“对象何时可用”。一个应用不是把对象 new 出来就能服务请求：标准路由扩展应在装配期贡献并冻结，生命周期参与者则在 initialize 中获取资源、完成内部接线，到 start 才绑定端口；还有的组件在 initialize 半途失败后需要特殊清理。本章建立一个只负责驱动参与者的 LifecycleOrchestrator，让启动、停止、回滚和状态都拥有可测试的顺序。

最终状态不是一个能处理 HTTP 的应用，而是一个可以放入 try-with-resources 的生命周期骨架。参与者按注册顺序完成全局 initialize，再按同一顺序完成全局 start；失败时已进入者逆序 stop，initialize 失败者调用 abort；正常停止逐个尝试所有参与者，把失败放进 ShutdownReport。应用代码负责创建和排序对象，生命周期引擎不读取配置，也不拥有 DI 容器。

## 涉及 ADR

- [ADR-0003：两阶段全局启动](../../docs/adr/0003-global-two-phase-startup.md)：所有 initialize 必须早于任何 start，防止 HTTP 端口先开放、路由后注册。注册顺序决定启动顺序，逆序决定停止顺序，组件不能自己增加 priority。代价是启动串行，一个慢参与者会延迟整个应用。
- [ADR-0004：启动失败自行回滚](../../docs/adr/0004-startup-rollback-preserves-the-original-failure.md)：已完成 initialize 的参与者逆序 stop，initialize 自身失败者调用 abort。启动原因始终是顶层异常，清理失败作为 suppressed 与 rollbackFailures 附加，不能替换真正病因。
- [ADR-0005：停机最佳努力并报告](../../docs/adr/0005-shutdown-is-best-effort-and-reported.md)：stop 捕获每个参与者的 RuntimeException 或 Error，继续清理其余对象，最后返回缓存报告。它本身不抛异常，因此调用方必须读取报告或日志，不能假设 close 会替自己失败。
- [ADR-0011：ThreadProvider 是带所有权的工厂](../../docs/adr/0011-thread-provider-is-a-factory-with-ownership.md)：提供者不是全局注册表，只创建并关闭自己产生的 executor。正常停止时先释放参与者，再关闭提供者，因为参与者在 stop 中仍可能提交清理任务。
- [ADR-0012：NEW 可以直接 STOPPED](../../docs/adr/0012-new-to-stopped-is-legal-and-idempotent.md)：未启动应用的 stop/close 合法且幂等，try-with-resources 不会因为 start 失败而再次抛清理异常。STARTING 中的 stop 不抢启动路径的回滚所有权，重启意味着重新构建。
- [ADR-0015：装配是应用职责](../../docs/adr/0015-application-assembly-is-a-responsibility-not-a-component.md)：框架不提供 ApplicationContext 式总管，应用在自己的代码里组合 Configuration、DI、Runtime 和 Lifecycle。启动失败由 orchestrator 回滚参与者，应用随后关闭 assembly；两条释放路径不互相调用。

## 步骤导航

1. [应用状态机与阶段划分](01-应用状态机与阶段划分.md)：从 `ApplicationState` 的六个状态出发，划清启动、运行、停止和失败的合法转换。
2. [参与者的启动顺序与依赖](02-参与者的启动顺序与依赖.md)：用注册顺序表达依赖，解释为什么优先级字段和组件自排序会把决定推给错误的人。
3. [两阶段启动：先配置后服务](03-两阶段启动-先配置后服务.md)：沿着 `initializeAll()` 与 `startAll()`，把“绑定端口”推迟到所有路由和配置就绪之后。
4. [启动失败时的回滚与原始异常](04-启动失败时的回滚与原始异常.md)：区分 abort 与 stop，保留原始失败，并如实标注当前线程提供者关闭边界。
5. [检查点：能 start 也能 stop](05-检查点-能start也能stop.md)：实际运行 25 项生命周期测试，验证顺序、回滚、幂等、重入停机与提供者关闭顺序。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `2670984` | 初始提交已带来 383 行 LifecycleOrchestrator、六个状态、参与者接口、回滚与停机测试 | 生命周期不是 HTTP 之后补的装饰，而是 core 的第一批职责 |
| `54930be` | 提交标题提到生命周期异常，实际 diff 只把 CI 从 verify 改为 clean verify | 提交标题不能代替 `git show`；实际证据以文件差异为准 |
| `dd3fd0d` | ThreadCapabilityUnavailableException 改收 RuntimeCapabilities，消息不再读全局 java.version | 底层异常只描述请求与运行期，不引用尚未存在的配置键 |
| `e0bc31b` | 配置快照加入 core，orchestrator 暂时获得读取配置的入口 | 便利入口会把 kernel 与 config 耦合，后续决定会删除它 |
| `e2c9902` | 显式 ServiceAssembly 加入，构建与生命周期成为两条路径 | 创建对象和驱动对象不是同一个职责 |
| `6aac12f` | 删除 Builder.configuration(...) 与 threadKind()，应用传入已解析 ThreadPolicy | ADR-0015 把组合责任交还应用，而不是新增总管类型 |
| `780af74` | 插件成为可自描述的 LifecycleParticipant | 参与者接口要承载后续真实消费者，而非只服务测试夹具 |

可用 `git show --stat 2670984`、`git show --unified=12 dd3fd0d` 和 `git show 6aac12f -- framework/barezen-core/src/main/java/com/barezen/core/kernel/LifecycleOrchestrator.java` 对照。注意本章按最终实现讲解，`e0bc31b` 的临时配置入口已经被 `6aac12f` 删除，不能把它教成当前 API。

## 本章完成后的能力清单

你应能解释为什么没有 INITIALIZED 状态，为什么 initialize 中不能绑定对外端口，为什么注册顺序比 priority 数字更可靠。你应能根据一次启动失败准确说出每个参与者会收到 stop 还是 abort，为什么失败参与者后面的对象根本不会进入回滚集合，以及为什么 stop 必须能处理“只 initialize、从未 start”的对象。

你还应能区分三种结果：StartupException 表示启动失败且参与者回滚已尝试；ShutdownReport 表示正常停机有清理噪声；LifecycleException 表示状态转换不合法。最后，你知道当前 start 失败分支释放参与者，但没有调用 threads.close()，FAILED 上的 stop 也不再次关闭提供者，因此不能把参与者回滚保证扩大成所有线程资源都已关闭的承诺。

## 不这样设计会坏掉什么

如果逐组件 init/start，HTTP 可能先接受请求，后注册的资源消费者尚未完成初始化，启动窗口会出现 404 或半配置响应。当前标准路由扩展在装配期已经完成，因此两阶段保护的是参与者资源准备，而不是把插件路由注册推迟到 initialize。如果让失败直接冒泡，先前创建连接池和文件句柄的组件无人释放；如果清理异常替换原始异常，运维会先追错一个 stop 失败，却错过真正导致启动失败的端口占用。

如果 stop 遇到第一个异常就退出，其余组件永远得不到清理；如果 NEW 状态禁止 close，try-with-resources 会在启动失败后再抛一个第二异常。反过来，如果生命周期引擎读取 Configuration 或通过 DI 创建对象，它就同时拥有策略、容器和生命周期三个角色，无法独立测试，也无法让应用清楚安排两条释放路径。

## 下一章预告

第四章把驱动好的生命周期接到 HTTP 契约。第三章的参与者可以启动，但还没有 Request、Response、Headers，也不知道怎样把响应写成字节。下一章先不选择 JDK 或 Servlet，而是定义不依赖服务器的契约，并处理无响应体状态，让路由器与适配器都能围绕同一组类型工作。
