# 第 4 章：HTTP 契约不等于服务器

## 本章目标

第三章的应用已经能 start 和 stop，但参与者之间还没有一种与服务器无关的消息语言。如果 Handler 直接接收 HttpExchange 或 ServletRequest，路由、安全装饰器和业务代码都会绑定同一个传输实现；以后增加第二个适配器，不是增加一个模块，而是重写应用。

本章先建立 Request、Response、Headers、HttpMethod 和 Handler，让应用只看见客户端发送了什么、自己准备回答什么。契约不绑定端口、不写字节、不选择路由、不解析查询参数，也不把异常自动翻译成 500。它只负责不可变值对象和一个同步函数签名，把传输细节留给后续适配器。

完成本章后，barezen-web 仍然不能独立提供 HTTP 服务，这是边界而不是缺口。Router 与适配器可以共享同一组类型，应用 Handler 可以在没有 socket 的测试里直接调用。等真实服务器加入时，只需翻译边界两端的对象，不改变业务处理方式。

## 涉及 ADR

- [ADR-0018：HTTP 是契约，不是服务器](../../docs/adr/0018-http-is-a-contract-not-a-server.md#L55)：建立五类公开类型、原始路径/查询、内存字节体、不可变响应与无服务器依赖。它是本章的设计底座。
- [ADR-0020：路由参数契约](../../docs/adr/0020-route-parameters-contract.md)：后续把 Handler 从 `handle(Request)` 改为 `handle(Request, Map<String,String>)`。ADR-0018 的旧签名是历史快照，不能直接复制到当前代码。
- [ADR-0027：响应写出边界](../../docs/adr/0027-http-response-body-semantics.md)：无响应体状态和分帧字段的拒绝规则，以及 HEAD 由适配器处理的理由。
- [ADR-0029：响应分帧与 304 长度](../../docs/adr/0029-response-framing-and-304-length.md#L106)：把状态、方法、body 与 Content-Length 固化为矩阵；当前 304 不发 Content-Length，也不发 body。
- [ADR-0002：JDK 17 基线](../../docs/adr/0002-jdk-17-baseline-with-runtime-capability-instead-of-jdk-modules.md)：契约不借用更高 JDK 的客户端/服务器类型；项目按 release 17 编译，运行期能力由 core 探测。

ADR 与代码冲突时，教程以当前源码为准，并保留演进原因。例如 ADR-0018 第 53 题仍写单参数 Handler，第 14 节展示的适配器也没有今天的请求体上限和 HEAD 长度处理；这些是当时设计，不是可以直接粘贴的最终实现。

## 步骤导航

1. [为什么契约不等于服务器](01-为什么契约不等于服务器.md)：把“应用看见什么”与“服务器如何搬运”分开，确定零编译依赖和 Handler 边界。
2. [Request 的形状与读取语义](02-Request的形状与读取语义.md)：保留原始路径/查询，用 Optional 表达缺失，用字节副本保证所有权的清晰。
3. [Response 的形状与写出语义](03-Response的形状与写出语义.md)：返回而不是写入，让装饰器仍能修改答案，同时承认内存响应体的限制。
4. [Headers 与无响应体状态](04-Headers与无响应体状态.md)：保留重复字段，拒绝控制字符和非法分帧，解释 204/304/HEAD 的不同 wire 行为。
5. [检查点：契约可独立于服务器测试](05-检查点-契约可独立于服务器测试.md)：运行模块测试与 compile dependency tree，把“没有服务器依赖”变成构建事实。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `32ebc41` | 17 个文件、3109 行新增：web POM、五类契约、ADR-0018、架构测试与单元测试 | 契约从第一版就有测试和依赖边界，不是先写 API 再补理由 |
| `2eddb0d` | Handler 增加原始参数 Map，删除单参数重载；同步修改 RouteMatch 和测试 | 路由捕获结果不再被 Handler 二次解析 |
| `74ea0fb` | OPTIONS * 的 JDK 实测，Router 小改 | Request 接受 `*` 不是凭空加字段，而是真实请求目标测试推动 |
| `ef1f5db` | Response 增加 1xx/204/304 无体与分帧字段拒绝 | 非法形状在构造时暴露，不让服务器静默丢弃 |
| `fc7f72c` | JDK HEAD 写 representation length、不写 body | HEAD 的 wire 行为属于适配器，不属于 Response |
| `5822a04` | HEAD 判断改回原始方法 token | 方法 token 的大小写边界不能靠“看起来像 HEAD”猜测 |
| `ace40f5` | 一张表断言八种分帧组合 | 单个测试各自正确仍可能组合出错，矩阵把整体契约钉住 |
| `de55b51` | 记录方法大小写与 304 决策 | ADR-0028/0029 为后续边界提供可追溯依据 |

用 `git show --stat 32ebc41` 看初始规模，用 `git show 2eddb0d -- framework/barezen-web/src/main/java/com/barezen/web/Handler.java` 看签名变化，再用 `git show ef1f5db -- framework/barezen-web/src/main/java/com/barezen/web/Response.java` 看构造校验如何进入契约。注意提交时间不是严格按教程顺序线性展开，矩阵测试和 HEAD 修复晚于初始契约。

## 本章完成后的能力清单

你应能画出一条边界：适配器把服务器请求翻译成 Request，Router 选出 Handler 并生成参数 Map，Handler 返回 Response，适配器决定怎样写状态行、字段和 body。你应能解释为什么路径不解码、为什么 query 不解析、为什么 body 用 byte[]、为什么响应没有 send，以及为什么重复 Header 不能塞进 Map。

你还应能区分三种“空”：没有 query 与空 query；没有 Header 与空值 Header；没有 body 与零长度 body。前两者在契约里有可观察差异，第三者被统一为空数组，wire 上的 Content-Length 由适配器决定。最后，你知道当前 304 不发 Content-Length 是 ADR-0029 的明确选择，而不是漏实现。

## 不这样设计会坏掉什么

让契约依赖 HttpExchange，会让每个 Handler 的单元测试都隐式启动服务器，安全装饰器也无法在不构造 socket 的情况下验证拒绝路径。让 Handler 接收可变 Request，会把“请求是什么”变成组件之间共享的草稿本，后一个组件永远不知道前一个改了什么。让 Response 提供输出流，会让业务代码在装饰器之前把状态和部分字节写死。

反过来，把契约做得过于完整也有代价：通用 URI 解析会替应用决定 `%2F` 和 `..` 的含义，通用 Header Map 会丢重复值，JSON helper 会替所有应用选序列化规则，异步签名会把线程池和生命周期偷偷塞进每个 Handler。本章宁可留下明确边界，也不制造一个没有真实消费者的万能上下文。

## 下一章预告

第五章将让契约第一次有消费者：Router。路由表只负责把 method 与原始 path 匹配到一个 Handler，并生成参数 Map；它不调用 Handler，也不负责写 404/405。下一章会推导字面量优先级、重复形状拒绝、参数段只能是一个非空原始段等规则。
