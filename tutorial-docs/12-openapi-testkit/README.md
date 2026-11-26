# 第 12 章：OpenAPI 投影与 TestKit

前十一章已经把框架的主干搭完：契约、路由、适配器、生命周期、配置、静态内容、安全、数据和插件都有了真实实现。本章补上两件从“框架能运行”走向“外部能够理解并验证它”的事：把应用**声明过**的 API 投影成稳定的 OpenAPI JSON，以及用真实套接字验证应用公开的 HTTP 行为。

这里最容易产生的误解，是把 OpenAPI 当成路由表的自动副产品。当前 `Router` 没有路由枚举 API，`OpenApiPlugin` 也不读取它；应用在装配时同时写路由和 operation metadata，再由 `Operation` 补充参数、请求体、响应和安全要求。声明重复不是优雅，而是这份实现明确接受的代价：至少两处文本由同一段应用代码维护，阅读者能在同一位置发现漂移，框架也不必为了文档反向侵入 web 契约。

第二个误解是把 TestKit 当成另一套 HTTP 实现。它恰恰不实现服务器：`TestServer` 包装真实的 `JdkHttpServerAdapter`，绑定 `127.0.0.1:0`，客户端使用 JDK 的 `java.net.http.HttpClient`，响应经过真实分帧后再成为不可变快照。直接调用 `Handler` 的单元测试仍有价值，但它无法证明方法、请求头、响应体和无体状态真的通过了适配器；TestKit 补的是这一段证据。

本章的四步如下。

| 步骤 | 文件 | 要解决的问题 |
|:---|:---|:---|
| 1 | [01-从元数据到声明式投影.md](01-从元数据到声明式投影.md) | collector 只记录路线身份，如何增加参数、schema、响应和安全声明而不写回 collector |
| 2 | [02-确定性JSON与拒绝条件.md](02-确定性JSON与拒绝条件.md) | 为什么稳定输出由模型排序和手写 emitter 共同保证，六类无效声明在哪里失败 |
| 3 | [03-TestKit用真实套接字作证.md](03-TestKit用真实套接字作证.md) | 测试工具为什么独立成叶子模块，如何观察端口、响应、身份和数据库隔离 |
| 4 | [04-可运行示例与统一检查点.md](04-可运行示例与统一检查点.md) | 如何把文档装配、真实服务和黑盒测试串起来，并如实记录能力边界 |

## 本章依赖的决定

- [ADR-0043](../../docs/adr/0043-openapi-generator-is-a-declared-data-projection.md) 决定生成器留在 `com.barezen.plugins.openapi`，使用声明式 schema，只输出 JSON，不反射、不扫描、不扩成完整 3.0.3 语法。
- [ADR-0044](../../docs/adr/0044-testkit-is-a-downward-only-test-tool.md) 决定 TestKit 是单向依赖 core、web、JDK adapter、security、data 的消费者测试工具；框架与插件都不依赖它，它也不依赖插件。
- [ADR-0045](../../docs/adr/0045-examples-are-a-leaf-public-api-only.md) 决定示例是一个叶子模块，只使用公开 API，真实端口由 `main` 启动，JUnit 与 TestKit 在 test scope 验证同一入口。
- [ADR-0042](../../docs/adr/0042-plugins-sit-on-the-framework-never-in-it.md) 继续约束插件放在框架之上而不是框架内部；因此 OpenAPI 生成器不另开一个会让框架依赖插件类型的新模块。
- [ADR-0019](../../docs/adr/0019-router-semantics.md) 的路径模板语义是 operation 参数校验的参照；[ADR-0029](../../docs/adr/0029-response-framing-and-304-length.md) 决定 204/304 无体，文档不得给它们声明 body schema。
- [ADR-0036](../../docs/adr/0036-sessions-are-opaque-and-revocable.md) 与 [ADR-0037](../../docs/adr/0037-status-codes-and-default-deny.md) 说明为什么当前 OpenAPI 子集只诚实地描述 bearer scheme，以及为什么测试要覆盖 401/403；[ADR-0038](../../docs/adr/0038-jdbc-boundary-and-driver-free-data-layer.md) 决定数据库由应用提供 `DataSource`；[ADR-0032](../../docs/adr/0032-class-major-build-contract.md) 继续要求每个产物保持 major 61。

这些 ADR 是决定记录，不是替代源码的 API 文档。当前代码若已经细化或修订早期表述，本章以磁盘源码和实际测试输出为准，并明确标出差异。

## 真实提交地图

| 提交 | 标题 | 本章要观察的变化 |
|:---|:---|:---|
| `68e0c672b6f7610b4840d7f7a1353e6c14655285` | `feat(plugins): 实现首批插件` | `OpenApiPlugin` 只收集 `OperationMetadata`，此时还没有文档生成器 |
| `e49587f7bb0a347ad0e215131a9c9aa1c40cbcfe` | `feat(plugins): 实现 OpenAPI 模型与确定性 JSON 生成器` | 新增九个生产类型，建立声明式模型、build 校验和 JSON 投影 |
| `fae6b463b85701c4263c23212194525550d91a7f` | `docs: 记录 OpenAPI 生成器决策并补齐覆盖` | 记录 ADR-0043，补 README 示例、无体响应和引用检查 |
| `743ae96758b58dd3ad2bdf472df21650af2caf07` | `feat(testkit): 新增集成测试工具模块` | 新增真实 server/client、快照、断言、fixtures、database reset 与六条架构规则 |
| `2b98fe2b081281b3b221eff79506e203dabea0d3` | `docs: 补充 TestKit 指南与 ADR-0044` | 把测试边界写进指南，并让 README 示例由真实 socket 测试执行 |
| `0971abc64b56f72430f572ffa9536cd0815997c5` | `feat(examples): 新增七个可运行示例` | `OpenApiExample` 把声明、handler 路由、`main` 和 TestKit 测试连成完整消费者样例 |

建议按提交而不是按最终文件阅读。例如先用 `git show e49587f --stat` 看模型一次进入多少文件，再读 `Operation.java` 与 `ApiDocument.java` 如何分工，最后看 `fae6b46` 在哪些地方补了测试。这样能分清“主体设计”和“后来被失败测试逼出的修补”，不会误以为所有校验从第一行就同时存在。

## 学完以后能够检查什么

完成本章，你应该能独立回答并验证这些问题：

1. 一份文档的信息从哪里来？哪些由 collector 提供，哪些必须由应用补充，哪些字段由生成器派生？
2. 为什么 `$ref` 不会让自引用 schema 无限递归？引用节点与组件定义各自承担什么校验？
3. 相同声明以不同顺序装配，为什么仍得到相同字节？哪些集合必须排序，哪些集合必须保留声明顺序？
4. 空 title、重复 operation、重复 operationId、无响应、悬空 `$ref`、未声明 security scheme 分别在哪个构造点失败？
5. TestKit 为什么不能依赖 plugins，而应用测试却可以同时使用 plugins 和 TestKit？
6. 真实 HTTP 测试与直接调用 handler 的测试各自能证明什么？为什么 3xx、端口释放和身份隔离不能靠 fake server 判断？
7. `TestResponse` 为什么复制字节并使用大小写无关的 header map？`TestDatabase.close()` 拥有什么，又不拥有什么？
8. 示例的 `main` 与 JUnit 测试是否运行同一份 document/router？实际输出能否与源码逐项对应？

## 一次失败推演

假设路由中有 `GET /users/{id}`，文档却声明 `Parameter.query("id", Schema.string(), true)`，并给 204 响应配了 JSON body。第一次写文档的人可能认为“参数名相同就够了”，但位置错会把客户端生成错误请求；204 有 body 则违反 HTTP 分帧契约。当前实现不会等生成 JSON 后才警告：`Operation.Builder.build()` 会把 query 与模板中的 path 参数冲突拒绝，`ApiResponse` 构造时直接拒绝 204 body schema。文档对象根本不会存在，更不会被挂到路由上。

再假设测试只调用 `document.handler().handle(...)`。它能验证 handler 返回了预期字节，却跳过了 JDK adapter 的 Content-Length 计算、HEAD 去除 body、连接关闭与套接字绑定。TestKit 的价值就是不让这些步骤被省略：应用构建公开 router，`TestServer.start` 启动真实适配器，client 通过 HTTP 请求，最后才对状态、header 和 body 做断言。若测试没有 close server，端口会一直占用，因此 try-with-resources 不是风格偏好，而是资源所有权的一部分。

## 诚实的边界

当前 OpenAPI 输出固定为 3.0.3 JSON，只有 `application/json` 一种媒体类型，schema 子集没有 `oneOf`、discriminator、vendor extension，也没有 YAML、HTML 或 Swagger UI。它不读取 Router，不校验完整 3.0.3 外部语法，更不会证明文档与实际 handler 行为永远一致；应用仍需为这份声明写测试。`OpenApiPlugin` 是装配期、非线程安全的 mutable collector，公开 `clear()` 可清空状态；不可变 operation 快照只防止快照被修改，不提供并发读写安全，也不建立 Router 与 metadata 的自动一致性。

TestKit 只包装 JDK adapter，没有 fake server、HTTP/2、WebSocket、SSE、multipart 或 Testcontainers。它的 JSON reader 是包内实现，只服务于断言，不是可公开依赖的 JSON 库；点分路径无法访问自身含点的 key。`TestHttpClient` 可接受绝对 URL，因此它也不是只能访问本机的安全沙箱。数据库 fixture 只执行调用者给出的单条 reset SQL，不关闭传入的 `DataSource`，不提供事务清理或并发迁移管理。

本章最后会把实跑结果固定为 179 项选定测试、九组 major 61 检查，以及 OpenAPI 示例的 1201 字节真实响应；同时说明覆盖率代理对 JDK SQL 接口产生的 `IllegalClassFormatException` 噪声，不把它误报成测试失败，也不通过修改源码掩盖它。
