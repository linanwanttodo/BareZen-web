# 第 7 章：配置与显式装配

## 本章目标

前六章已经得到生命周期内核、HTTP 契约、路由器和真实 JDK 服务器，但应用仍然散落着几个必须现在回答的问题：端口与线程策略从哪里读，谁能修改它们；谁负责调用构造函数；服务何时创建；启动失败后已经打开的连接池由谁关闭。本章不是增加一个“配置文件工具”或一个注解容器，而是把这些决定放到一条单向、可阅读的装配链上。

最终形状只有五个词：`Configuration → RuntimeConfig → ThreadPolicy → ThreadProviders.create(...) → constructor`。配置层复制进程输入并解析为类型化快照，应用把快照解析成运行时值对象，线程子系统只接受策略和能力，`ServiceAssembly` 只执行应用写下的工厂，生命周期引擎只驱动已经构造好的参与者。没有任何一层可以回头查询全局配置，也没有任何业务对象能长期持有装配器。

这里必须区分时间线与最终架构。`e0bc31b` 最初把 `LifecycleOrchestrator.Builder.configuration(...)` 作为解析入口；`6aac12f` 后来删除了它和仅供测试使用的 `threadKind()`，把组合责任交还应用。因此教程讲第一次建造时可以展示旧提交，但读者复制当前 API 时必须使用应用侧解析，不能照抄已经删除的便利方法。

完成本章后，你应能从一份 properties、环境变量和 `-D` 参数推导唯一赢家，说明拼写错误为什么不能用默认值掩盖；能阅读工厂函数看清依赖；能解释注册顺序为什么不是依赖顺序；能证明循环依赖在 `StackOverflowError` 前失败；还能为“构建失败”和“启动失败”分别指出释放者与顺序。

## 涉及 ADR

- [ADR-0005：关闭是尽力而为并报告，而非抛出](../../docs/adr/0005-shutdown-is-best-effort-and-reported.md)：生命周期 `stop()` 逐个尝试参与者并返回 `ShutdownReport`。它不把失败变成控制流，也不让一个参与者阻止其余清理。这个原则影响本章如何理解关闭，但 `ServiceAssembly.close()` 有自己的聚合异常契约，不能混为一谈。
- [ADR-0011：ThreadProvider 是带所有权的执行器工厂](../../docs/adr/0011-thread-provider-is-a-factory-with-ownership.md)：provider 不是全局注册表，只关闭自己创建的 executor。AUTO 可回退，显式 PLATFORM/VIRTUAL 不可悄悄降级。相同语义由平台与虚拟实现的共同一致性测试证明。
- [ADR-0013：配置在装配时解析一次并以类型化值传递](../../docs/adr/0013-configuration-is-resolved-at-assembly-time.md)：来源、优先级和转换集中在 config 包；组件接收值而不是配置句柄。快照不热更新，错误的赢家不会被低层默认值替换，错误消息由知道来源的配置层报告。
- [ADR-0014：依赖注入是显式工厂注册](../../docs/adr/0014-dependency-injection-is-explicit-factory-registration.md)：工厂体直接写构造调用，容器不扫描、不反射选构造器。所有注册在 build 时急切创建，循环与缺失在启动前失败；创建者拥有关闭责任，外部实例由调用者释放。
- [ADR-0015：应用装配是应用的责任](../../docs/adr/0015-application-assembly-is-a-responsibility-not-a-component.md)：框架不提供 ApplicationContext 或第二个生命周期引擎。应用按顺序组合配置、能力、DI 与生命周期，停止时先停止参与者再关闭 DI 创建的基础设施。它取代了 ADR-0013 中把 configuration 入口放在 orchestrator 的旧决定。

## 步骤导航

1. [配置源与合并解析](01-配置源与合并解析.md)：把默认值、文件、环境、系统属性放进固定优先级，理解快照、类型转换与来源错误。
2. [装配期一次性解析的取舍](02-装配期一次性解析的取舍.md)：沿 `e0bc31b` 到 `6aac12f` 的修正，解释为什么最终入口在应用而不在生命周期引擎。
3. [显式工厂注册替代反射扫描](03-显式工厂注册替代反射扫描.md)：实现两操作注册、急切解析、循环路径、一次性构建和不能泄露的 Services 视图。
4. [线程提供者作为工厂](04-线程提供者作为工厂.md)：把配置决定的 ThreadPolicy 接到能力探测与 provider 所有权，验证 AUTO 与显式策略的不同规则。
5. [检查点：装配失败释放已建对象](05-检查点-装配失败释放已建对象.md)：跑配置、装配、生命周期边界和线程一致性测试，观察反向释放及原始异常不被清理覆盖。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `2670984` | 生命周期、运行时探测、两种 ThreadProvider 与一致性测试建立 | provider 先于配置层存在，且已经是局部工厂而非全局池 |
| `e0bc31b` | 19 文件、1677 行新增，配置包、RuntimeConfig、orchestrator 入口及端到端测试 | 第一次让一个设置穿过多个来源并落到线程决策 |
| `e2c9902` | ServiceAssembly、Services、AssemblyException 与 616 行装配测试 | 显式工厂替代反射，构建与生命周期保持分离 |
| `79790a5` | build 失败时释放已经创建的对象，补 3 个失败路径测试 | 修复“没有返回 assembly，因此无人可关”的真实泄漏 |
| `6aac12f` | 删除两个公开便利方法，增加 ApplicationAssemblyBoundaryTest 与架构规则 | 把组合责任交还应用，并用 API 表面断言固定边界 |

按提交阅读，可以看到答案不是一次设计到底：

```bash
git show --stat e0bc31b
git show e0bc31b -- framework/barezen-core/src/main/java/com/barezen/core/config/MergedConfiguration.java
git show e2c9902 -- framework/barezen-core/src/main/java/com/barezen/core/di/ServiceAssembly.java
git show 79790a5 -- framework/barezen-core/src/main/java/com/barezen/core/di/ServiceAssembly.java
git show 6aac12f -- framework/barezen-core/src/main/java/com/barezen/core/kernel/LifecycleOrchestrator.java
git show 6aac12f -- framework/barezen-core/src/test/java/com/barezen/core/kernel/ApplicationAssemblyBoundaryTest.java
```

## 本章完成后的能力清单

你应能用 `Configuration.of(...)` 自己排列来源，并用 `Configuration.standard(Path)` 复现生产顺序。key 的类型与默认值属于声明，转换属于统一实现，来源只回答“有没有这个键”。String、Integer、Long、Boolean、Duration 与 enum 是当前支持类型；primitive 类型不会被偷偷支持，YAML 与动态重载也不存在。

你应能手写 `RuntimeConfig.from(configuration)` 这类窄绑定，不引入通用 binder。你应能从工厂体看出每个构造依赖，接受一个类型只能注册一次、只有应用级单例、构建后不可再创建新对象。Services 只在工厂执行期间可用，泄露它会被拒绝，这是防止“装配器变成 service locator”的实际机制，而不只是文档约定。

资源归属同样要能画清：factory 创建且实现 AutoCloseable 的对象由 assembly 关闭；instance 创建的对象不由 assembly 关闭；生命周期参与者由 orchestrator 驱动；orchestrator 自建 ThreadProvider 时关闭它。关闭顺序是参与者在前、assembly 在后；assembly 内部则按创建的逆序。线程能力是否满足在 orchestrator build 时决定，而不是等第一次请求。

## 不这样设计会坏掉什么

让每个组件直接读 `System.getProperty`，会让同一个对象在两个部署中行为不同，测试也要修改全局状态；让低优先级来源在高层值转换失败后继续查找，会把操作员的拼写错误隐藏成另一个来源的默认值。若把 Configuration 长期注入组件，运行中有人改系统属性就可能让两个组件对同一设置产生不同答案，而这正是快照要消除的竞态。

反射选构造器会把依赖藏到容器运行过程里；缺失依赖延迟到首个请求才暴露，循环依赖则以 StackOverflowError 结束；给同名注册加 Primary 或 qualifier 又会把“选哪个实现”的责任转回容器。若 build 只返回完整图却不处理半途失败，调用者手中没有 assembly 可关闭，先打开的池和文件句柄就成了孤儿。这是 `79790a5` 修复的核心，不是可以留给使用者的注意事项。

若让 DI 自己 start/stop 对象，就会出现两个生命周期引擎；若在 orchestrator 中保留 configuration 句柄，kernel 会反向依赖 config，配置决策也离开应用。若关闭时先关 assembly，参与者的停止逻辑可能仍在使用连接池，错误会伪装成参与者故障。若 ThreadProvider 做成全局共享，关闭 HTTP 子系统就会误停事件总线；若显式 virtual 在 JDK 17 上降级，部署者会得到与请求不同的运行模型。

## 下一章预告

第 8 章把已经装配好的对象接到静态内容边界：一个资源对应一个 Handler，条件请求决定是否返回 304，检查点验证真实资源可访问。配置与装配不再膨胀成新的调度中心，它们只负责在启动前把明确的对象和值准备好。
