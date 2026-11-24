# 01 适配器契约与 Provider 发现

## 这一步要解决什么

到这里，Request、Response、Handler 和 Router 已经能在内存中完成一次匹配，但没有代码拥有一个可 start、可 stop、可报告端口的服务器。若直接在应用里写 `HttpServer.create(...)`，JDK 类型会渗入业务装配，未来换成 Servlet 容器就必须修改 Handler 或 Router。本步先冻结两端之间最小的接缝：应用知道“有一个 HTTP 服务器适配器”，适配器知道 Router，但双方都不需要知道对方怎样处理字节。

这里实际有两个接口，职责不能合并。`HttpServerAdapter` 是运行时对象，负责一个服务器的生命周期和地址；`HttpServerAdapterProvider` 是工厂，负责按 host、port、Router 创建一个尚未启动的新实例。Provider 不保存创建结果，不缓存 Router，也不提供 get-or-create。若把两者合并，调用方每次想启动服务器都可能拿到上一台已经运行或已经停止的实例，“一个实例一个生命周期”的规则就无处安放。

本步还要决定谁调用 ServiceLoader。BareZen 的接口公开只为让 JDK 实现被标准 SPI 发现，但核心模块并不在内部扫描 classpath、排序 provider 或隐式选择 `"jdk"`。发现属于应用装配层；Provider 只声明能力，不成为全局服务定位器。这样测试可以显式 new 一个 adapter，应用也可以按稳定 id 选择实现，不会因为 classpath 多一个依赖就悄悄改变服务器。

## 先想清楚再写

`port()` 返回 `OptionalInt`，而不是在未启动时返回 0 或 -1。0 是构造参数中“请操作系统分配端口”的合法请求，但已启动服务器的实际端口通常不是 0；把两者混用会让调用者无法区分“尚未绑定”和“绑定失败”。start 后从真实 server address 读取端口，stop 后清空 server，因此 OptionalInt.empty 表达的是一个状态事实，而不是一个魔法数字。

Provider 的 `id()` 是稳定选择键。若用实现类名作为 id，重命名或换包就会破坏配置；若用全限定类名兼作选择器，应用就必须依赖具体模块。`"jdk"` 足够短，也和后来的 `"servlet"` 形成同一层选择语义。id 不参与自动优先级，因为“哪个 provider 优先”不是 Provider 自己能回答的问题。

`create` 只构造而不绑定，是为了把配置错误与 I/O 失败放在不同时间点。blank host、非法 port 或 null Router 可以在调用工厂时拒绝；未知主机虽然能构造 `InetSocketAddress`，却只有 start 时才需要 DNS 解析和 socket bind。若工厂提前启动，测试就不能获得一个 fresh adapter，应用也不能在统一生命周期阶段执行启动，启动失败回滚还会缺少清晰的参与者边界。

不缓存创建结果同样是必要的。一个进程可以有两个不同 Router 或两个临时端口；Provider 若保存“最后一个 server”，第二次 create 会意外共享状态。它也不实现 Plugin 或 LifecycleParticipant：Provider 是描述怎样创建的对象，不是需要被生命周期启动的参与者。适配器实例本身也不是核心 LifecycleParticipant，而由应用编排器显式持有并 start/stop。

公开最小表面并不等于 JDK 实现也最小。`JdkHttpServerAdapter` 当前有三个 public 构造，分别用于默认线程、借用 ThreadProvider 和读取 Configuration；这是具体实现的装配入口。Provider 故意不把这些参数塞进三方法工厂，否则 TLS 或 body limit 每增加一个配置，公共 SPI 就要再改一次。具体实现应该通过已有 Configuration 快照吸收新配置，而不是让所有 provider 被迫理解 JDK 专属键。

## 动手

- [HttpServerAdapter.java:36](../../framework/barezen-web/src/main/java/com/barezen/web/HttpServerAdapter.java#L36)：公开 `AutoCloseable` 契约，start、stop、isRunning、port 四个方法。
- [HttpServerAdapter.java:84](../../framework/barezen-web/src/main/java/com/barezen/web/HttpServerAdapter.java#L84)：默认 close 调用 stop，使 try-with-resources 与显式停止语义一致。
- [HttpServerAdapterProvider.java:33](../../framework/barezen-web/src/main/java/com/barezen/web/HttpServerAdapterProvider.java#L33)：只有 `String id()` 与 `HttpServerAdapter create(String host, int port, Router router)`。
- [JdkHttpServerAdapterProvider.java:24](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkHttpServerAdapterProvider.java#L24)：包内公开实现，稳定 id 为 `"jdk"`。
- [JdkHttpServerAdapterProvider.java:52](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkHttpServerAdapterProvider.java#L52)：校验参数并构造 `InetSocketAddress`，但不 bind、不 start。
- [META-INF/services/com.barezen.web.HttpServerAdapterProvider](../../framework/barezen-http-jdk/src/main/resources/META-INF/services/com.barezen.web.HttpServerAdapterProvider)：一行注册 `com.barezen.http.jdk.JdkHttpServerAdapterProvider`。
- [JdkHttpServerAdapterProviderTest.java:40](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterProviderTest.java#L40)：真实 classpath 只发现 `"jdk"`。
- [JdkHttpServerAdapterProviderTest.java:77](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterProviderTest.java#L77)：同一 provider 创建的两个 adapter 是独立服务器。
- [JdkHttpServerAdapterProviderTest.java:147](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterProviderTest.java#L147)：反射检查公开方法只有 id、create 和 Object 方法。
- [JdkHttpServerAdapterProviderTest.java:160](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterProviderTest.java#L160)：禁止 Provider 变成 service locator。
- [JdkHttpServerAdapterProviderTest.java:223](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterProviderTest.java#L223)：未知主机可以构造，bind 错误延迟到 start。
- [ADR-0025](../../docs/adr/0025-http-adapter-provider.md)：接口形状、三参数上限、fresh unstarted adapter 和发现责任。

`a2e05fa` 已经带来 JDK adapter、bridge、dispatcher 和首批端到端测试，`65fed37` 才补上 Provider 与 SPI。阅读时不要把最终的三构造 JDK adapter 当成初始 Provider 的参数：Provider 仍然只有 host、port、Router，线程策略、body limit 和 TLS 都在具体 adapter 的其它构造路径中解决。

## 常见的第一次写错

最常见的是把 Provider 当作 IoC 容器：加入 list、find、默认实例或按类名缓存，最后所有应用共享一个不可重建的 server。另一个错误是让 ServiceLoader 在库内自动运行并选第一个实现。这样测试进程与生产进程可能因为依赖顺序得到不同 provider，用户也没有地方声明明确选择。

把未知主机错误提前到 create 看似“尽早失败”，实际混淆了参数形状与运行环境。构造地址不要求主机当前可解析；统一启动阶段才拥有 bind 失败上下文，也才能逆序回滚已经启动的参与者。反过来，把明显非法的 port 留到 start 也不好，因为这不是环境故障，而是调用方拼错了配置。

只检查 provider 实现的方法数量还不够。若新增静态 mutable registry，表面方法仍可能只有两个，但全局状态已经回来；若 Provider 持有 Router，两个 create 会交叉污染。因此测试同时检查字段、依赖词汇、创建独立性和 SPI 内容。教程的目标不是“接口看起来小”，而是任何共享服务器状态的实现都难以通过这组约束。

还要避免用高级 HTTP 客户端验证 Provider 发现。发现是 classpath 和 service 文件问题，与请求字节无关；而独立服务器测试必须真的 bind 两个端口。若只 mock `ServiceLoader`，就无法证明发布包中的资源路径正确。真实注册文件和真实 socket 组合，才是本步最便宜的外部视角。

## 怎么确认这一步没造错

先运行 Provider 聚焦测试：

```bash
mvn -o -B -ntp -pl framework/barezen-http-jdk -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=JdkHttpServerAdapterProviderTest
```

当前为 18 项：Discovery 4、Contract 5、Arguments 4、Registration 3、Limits 2。Discovery 通过真实 `ServiceLoader.load(HttpServerAdapterProvider.class)` 读取 classpath；Registration 检查资源文件存在、非空且每行都是实现类。两者必须同时存在，因为只写注解或只放 service 文件都不能完成标准 SPI 注册。

Contract 组检查创建结果：provider 没有可变 server/router 字段，不查询 registry，也没有 start 已创建 adapter 的副作用。两个 adapter 使用不同临时端口并各自响应，证明 create 没有返回共享实例。Arguments 组覆盖 null、blank host、-1、65536 等边界；Limits 组用真正占用端口证明 create 不 bind，占用释放后创建对象仍可在之后 start。

还可以做反向验证：给 Provider 增加 `get(String)` 或 `registry()`，表面规则应立即失败；在 create 中调用 start，未启动契约测试应失败；让两次 create 返回同一字段持有的 adapter，独立服务器测试应失败；删掉 SPI 文件，Discovery 与 Registration 都应失败。若这些改动后测试仍绿，说明保护没有瞄准真实接缝。

架构测试同时保证 `barezen-web` 不依赖 JDK adapter，JDK adapter 的公开签名不暴露 `HttpServer`，bridge 和 dispatcher 不成为顶层扩展点。Provider 测试验证“这一个接口很小”，ArchUnit 验证“依赖方向没有偷偷逆转”，两者不能相互替代。

## 此刻的代码长什么样

本步交付一个可替换、无全局状态的服务器接缝：四方法 `HttpServerAdapter`、两方法 `HttpServerAdapterProvider`、稳定 id `"jdk"` 和标准 SPI 注册。Provider 创建 fresh unstarted adapter，未知主机在 start 时才暴露，具体 JDK 配置不污染公共工厂。

同时交付 18 项 Provider 测试和相应的架构边界。此时尚未证明 bridge 是否保留原始 target、请求体是否有界、HEAD 是否正确；那些是后续步骤的独立问题。把创建契约与传输语义分开验收，可以在服务器实现变化时保留稳定的应用装配方式。
