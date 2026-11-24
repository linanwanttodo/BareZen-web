# 第 6 章：第一个真实 HTTP 服务器

## 本章目标

前五章得到了一组可以独立测试的契约：Request 保存一次请求的五项事实，Response 保存状态、Headers 和完整字节，Handler 同步返回 Response，Router 只给出 Handler 与参数。但它们都发生在内存里。本章要回答一个更实际的问题：TCP 连接上的字节怎样进入这些类型，Handler 的结果又怎样变成另一组字节？

答案不是让 `barezen-web` 认识某个服务器，而是增加一个可替换的适配器边界。`HttpServerAdapter` 只规定 start、stop、isRunning、port 和 AutoCloseable；`HttpServerAdapterProvider` 只负责按 host、port、Router 创建一个未启动实例。JDK 实现独占 `com.sun.net.httpserver` 的知识，内部 bridge 负责 Request/Response 翻译，dispatcher 负责路由、异常和状态选择。这样，第四章的 Handler 不需要导入 JDK 类型，未来 Servlet 适配器也能复用同一张路由表。

完成本章后，你应能沿着一次真实请求讲清完整链路：服务器接受连接并创建 exchange，bridge 读取原始 target、Headers 和有界 body，dispatcher 查询 Router，Handler 返回 Response，bridge 按状态与原始方法 token 决定长度和 body 字节，最外层 finally 关闭 exchange。你也应能解释 404 与 405 的信息来源、413 为什么先于完整 body 读取、HEAD 为什么“无 body 但有 representation length”，以及停止服务器时哪些资源属于适配器。

本章不是 HTTP 全功能实现，也不把 JDK 包装成 Spring。没有 Servlet Filter 链、中间件 DSL、响应流、HTTP/2 配置、访问日志或自动条件请求。当前响应始终是完整 `byte[]`，当前 Router 仍是线性扫描，`OPTIONS *` 在当前 JDK 上不可达。TLS 是 `326c25a` 后来加入的能力：两个 keystore 配置键、一个包内 `Tls` 类，公开适配器表面并未因此增长。

## 涉及 ADR

- [ADR-0021：JDK HTTP Server Adapter](../../docs/adr/0021-jdk-http-server-adapter.md)：为什么只有 `barezen-http-jdk` 可以依赖 JDK server，以及根 context 与路由表的边界。
- [ADR-0022：Exchange Bridge](../../docs/adr/0022-exchange-request-response-bridge.md)：原始 path/query、Headers 转换、body 读取与响应 framing。
- [ADR-0023：HTTP Error Semantics](../../docs/adr/0023-http-error-semantics.md)：400、404、405、413、500 的归属；其中 `OPTIONS *` 的旧“设计债”判断已被 ADR-0026 修正。
- [ADR-0024：Lifecycle and Executor Ownership](../../docs/adr/0024-server-lifecycle-and-executor-ownership.md)：一个实例一次生命周期、借用与拥有 ThreadProvider、启动失败清理。
- [ADR-0025：HTTP Adapter Provider](../../docs/adr/0025-http-adapter-provider.md)：id/create 两方法接口、ServiceLoader 注册与发现责任外置。
- [ADR-0026：OPTIONS * Semantics](../../docs/adr/0026-options-star-semantics.md)：真实 JDK 在 context 查找前拒绝星号目标，而不是框架漏了一个分支。
- [ADR-0027：Response Body Semantics](../../docs/adr/0027-http-response-body-semantics.md)：三字段 Response、禁止无 body 状态携带字节、HEAD representation length。
- [ADR-0028：Method Case and HEAD Wire Boundary](../../docs/adr/0028-method-case-and-head-wire-boundary.md)：路由按折叠方法，wire 按原始 token。
- [ADR-0029：Response Framing and 304 Length](../../docs/adr/0029-response-framing-and-304-length.md)：304 无长度是明确决策，framing matrix 固定八种形状。
- [ADR-0030：Response Body Abstraction](../../docs/adr/0030-response-body-abstraction.md)：暂不引入流式响应，以及重新开启讨论的条件。
- [ADR-0031：Request Body Limit](../../docs/adr/0031-request-body-limit.md)：默认 1 MiB、配置解析、已知长度预拒绝与未知长度有界读取。
- [ADR-0050：TLS Keystore](../../docs/adr/0050-tls-is-named-by-a-keystore-not-flagged-on.md)：后续 HTTPS 能力，用配置存在性选择而非增加公开构造或布尔开关。

## 步骤导航

1. [适配器契约与 Provider 发现](01-适配器契约与Provider发现.md)：把服务器差异挡在四方法契约和两方法工厂之后。
2. [JdkExchangeBridge 的职责边界](02-JdkExchangeBridge的职责边界.md)：保留原始 target，转换 Headers，选择最小错误响应并 framed 写出。
3. [请求体抽象与大小上限](03-请求体抽象与大小上限.md)：用 limit+1 区分恰好相等与超限，并处理 413 和 drain 的时序。
4. [虚拟线程执行器与线程归属](04-虚拟线程执行器与线程归属.md)：启动、停止、幂等关闭与 ThreadProvider 所有权。
5. [方法大小写与 HEAD 分帧边界](05-方法大小写与HEAD分帧边界.md)：用 framing matrix 和真实 token 修复两处历史错误。
6. [检查点：真实套接字往返](06-检查点-真实套接字往返.md)：运行 Provider、生命周期、wire、并发、TLS、字节码和架构证据。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `a2e05fa` | 12 文件、2609 行新增：Adapter、Bridge、Dispatcher、RawHttp 和首批真实服务器测试 | 最小适配器不是先写一个 interface，而是同时建立可观察的 wire 证据 |
| `65fed37` | Provider 契约、JDK 实现、SPI 注册文件与 18 项 Provider 测试 | 创建与发现分离，框架核心不持有全局 adapter registry |
| `06435b7`、`bfef5d5` | 原始 socket 固定错误语义，24 条架构规则固定模块边界 | HTTP 行为与依赖越界都由可执行规则保护 |
| `ad634f7` | 固定启动失败时 provider 与端口的释放行为 | “创建过”与“拥有”是不同问题 |
| `74ea0fb`、`4c62a5e` | 实测星号 target，随后用 ADR-0026 修正旧定性 | 不可达边界不是添加一个 dispatcher 分支就能解决 |
| `fc7f72c` | HEAD 写 representation length，不发送 body | 初版“无 body 即无长度”被真实协议需求修正 |
| `5822a04`、`58848d3` | 改按原始方法 token 判断 HEAD，增加大小写矩阵 | 路由折叠与 JDK wire 精确比较不能混为一层 |
| `ace40f5`、`de55b51` | 用一张表固定 framing，记录 304 长度决策 | 空 body、无 body 状态和 HEAD 是三个不同事实 |
| `643667d`、`e90c7cd` | 增加 Response 防误用、BodyLimit、配置构造和 413 路径 | 内存 body 模型保留，但读取必须有上限 |
| `184c774` | 修复有界读取的缓冲区偏移错误 | 自动增长 buffer 后，read 的 offset 与 length 都必须有界 |
| `977d85f`、`4c9feda` | 固定 413 的 HEAD 分帧，字节码基线进入构建 | 错误响应也遵守 matrix；release 17 必须检查实际 class major |
| `326c25a` | 增加包内 Tls、HttpsServer 路径和 12 项 TLS 测试 | 后续能力沿用既有生命周期，不增加公开 API |

可以用下面的命令阅读时间线，而不是只看最终源码：

```bash
git show --stat a2e05fa
git show 65fed37 -- framework/barezen-web/src/main/java/com/barezen/web/HttpServerAdapterProvider.java
git show 5822a04 -- framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkRequestDispatcher.java
git show 184c774 -- framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/BodyLimit.java
git show 326c25a -- framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/Tls.java
```

## 本章完成后的能力清单

你应能实现一个最小 JDK 适配器，并说明为什么 bridge 与 dispatcher 是包内 final 类型而不是新扩展点。你应能用 `getRawPath()` 和 `getRawQuery()` 解释 `%2F`、`%2e%2e`、`+` 为什么不提前解码；能用 `Optional<RouteMatch>` 与 `methodsFor()` 区分 404 和 405；能说出 500 不向 socket 返回异常细节，但异常必须进入服务端日志。

你还应能按 framing matrix 推导普通 200、空 200、204、304、HEAD、404、405、500 的字段和字节。方法大小写要分两层：九个标准方法在 `HttpMethod.of()` 中折叠，扩展方法保持原样；只有原始 token 恰好为 `HEAD` 才抑制 body。对于请求体，默认上限是 1 MiB，配置为 0 表示不接受任何 body；错误配置在构造时失败，不等到有请求才报错。

最后，你应能把验证结果说准确。本章明确的绿色检查点是 197 项；完整适配器类目前 213 项，其中方法大小写类 16 项有 1 项已知 JDK 24 环境差异。不能把排除该类后的通过写成“完整模块全绿”，也不能为了绿灯修改框架去迎合一个 JDK parser 差异。

## 不这样设计会坏掉什么

如果让 Handler 接收 `HttpExchange`，第四章契约会被 JDK 绑死，第二个适配器无法复用；如果 bridge 使用解码后的 path，`%2F` 会改变段数，请求可能命中另一个资源。若 Router 自己生成 404/405，错误 Content-Type、Allow 排序和 HEAD framing 会散落到路由层，而 Router 并没有 exchange 可写。

若不限制请求体，一个未知长度或超大声明就能持续消耗内存；若超限后立即关闭 exchange，JDK 的 leftover drain 可能阻塞 413 的写出。若用折叠后的 `HttpMethod.HEAD` 决定 wire suppression，JDK 按原始 token 判断时会与适配器作出相反判断，产生错误长度。若把 304 当成普通空 200，就会错误发送 `Content-Length: 0`；若给 204 声明长度，真实客户端可能直接拒绝响应。

资源归属同样不能含糊。适配器自建 ThreadProvider 时负责关闭，借用时绝不能代关；stop 应幂等且不把参与者异常重新抛给调用者。把这些规则写成“应该”并不够，必须有端口复用、provider 存活、启动失败清理、停止后无线程和架构边界测试。否则一次生产停止就可能变成端口泄漏或误关应用共享执行器。

## 下一章预告

第七章进入配置与显式装配。真实服务器把请求送到 Handler，但应用仍需要决定配置从哪里合并、服务对象由谁创建、线程提供者归谁所有。下一章会把配置解析和对象装配放到应用可见的边界，明确一次性解析的失败清理，并继续保证 HTTP 契约不会依赖具体运行环境。
