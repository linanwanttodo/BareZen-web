# 第 12 章第 3 步：TestKit 用真实套接字作证

## 这一步要解决什么

前两步证明了 document 能生成固定 JSON，也证明了它的直接 handler 能返回正确字节。但测试如果只执行 `router.route(...)` 再手工调用 handler，就绕过了 HTTP adapter。此时请求行如何解析、Content-Type 如何成为响应头、body 如何按 UTF-8 读取、连接如何关闭，全都不是测试对象。一个 direct-handler 全绿的框架，完全可能在真实 socket 上返回错误状态或错误内容。

测试工具还需要减少重复脚手架。每个应用都在写 `new JdkHttpServerAdapter(...)`、找空闲端口、构造 `HttpClient`、同步读取 response、处理 checked exception、关闭 server；写完以后，不同应用的断言风格还可能不同。`743ae96758b58dd3ad2bdf472df21650af2caf07` 因此新增 `barezen-testkit`，`2b98fe2b081281b3b221eff79506e203dabea0d3` 再补上 ADR、指南和可执行 README 示例。它不是新的运行时框架，而是应用测试源码可以依赖的叶子工具。

[ADR-0044](../../docs/adr/0044-testkit-is-a-downward-only-test-tool.md) 把核心标准写成“integration 就是真的 HTTP”。因此 TestKit 不发明 fake server，也不把 handler 搬进内存 map；它包装当前唯一的 `JdkHttpServerAdapter`，绑定 `127.0.0.1:0`，让操作系统分配空闲端口。测试看到的状态码、header 和 body 都必须跨过 socket，不能从被直接调用的 handler 那里“借”答案。

这里与 OpenAPI 生成器有一个容易混淆的模块问题。生成器不能离开 `barezen-plugins`，因为让它进入一个新框架模块会迫使其他模块命名 plugins；TestKit 却可以成为新的顶级模块，因为它的 compile dependencies 全部向下：core、web、http-jdk、security、data。它自己**不依赖 plugins**。测试 OpenAPI 时，应用测试可以同时依赖 testkit 与 plugins，但工具模块始终只认识 Router、Handler 和 JSON 字节。

## 先想清楚再写

先决定 server 的边界。`TestServer.start(Router)` 接收完整路由快照，构造 adapter，调用 `adapter.start()`，然后读 `adapter.port()`。端口为 0 是操作系统分配，不是写死的测试端口；两个并发测试类因而不会为了 8080 互相冲突。读到的端口之后组成 `baseUri`，client 把相对路径解析到它。若 adapter 已 start 却不报告端口，start 直接失败，不能留下一个“看起来在运行但无法访问”的 server。

共享的 JDK client 固定两件事：HTTP/1.1 和 `followRedirects(NEVER)`。前者与当前 JDK adapter 的能力面一致；后者让 3xx 保持可见。如果客户端悄悄跟随 redirect，一个 handler 返回 302 的缺陷可能在最终页面上被断言成 200。测试工具的任务是观察应用，不是替应用修正响应。

client 不应要求应用理解 JDK request builder。`TestHttpClient` 暴露 GET、HEAD、OPTIONS、DELETE、POST、PUT、PATCH 七个快捷方法；POST/PUT/PATCH 接受 String body，编码为 UTF-8；通用入口是 `request(HttpMethod, String)`，随后可写 header、body 并 `send()`。相对 path 最常见，但 `http://` 与 `https://` 绝对 URL 也被接受。这说明它不是安全沙箱，不能在未授权目标上把测试工具当扫描器使用。

请求失败必须有框架自己的异常。`IOException` 与 `InterruptedException` 包装为 `TestRequestFailedException`，消息包含 method 与 URI；中断还会恢复线程 interrupt flag。若把 checked exception 原样抛给测试作者，“连接被拒绝”会被埋在陌生 API 的栈里；若直接吞掉，测试可能继续断言 null 并产生第二层误导。当前选择是保留 cause，同时给出可直接行动的请求上下文。

第二项设计是 `TestResponse` 同步快照。请求结束后，byte[] 已完整读入，响应不再连接 socket；header 被重组为以 `String.CASE_INSENSITIVE_ORDER` 排序的 `TreeMap`，相同名字不同大小写的 values 合并。包装使用 `Collections.unmodifiableMap`，不是 `Map.copyOf`：后者可能不保留 comparator，导致“大小写不敏感”的公共承诺在实现里失效。body 数组进入和返回时都复制，`bodyText()` 固定 UTF-8。

快照有什么实际价值？测试可以先发送两个请求，再检查第一个响应；它不会因为第二个请求覆盖 client 内部缓冲区而改变。对 OpenAPI 来说，还能比较两次响应的完整字符串或 `bodyText()`，确认生成器没有 per-request 漂移。若 TestResponse 是连接上的惰性 view，测试结束或 server 关闭后再读就可能失败，断言也会变成时序相关。

第三项设计是断言 API 保持小而可理解。`Assertions` 只有三个入口：响应、整数计数、`Optional<Principal>`。响应可检查状态、单个 header、header 存在、精确 body、body 子串，以及 JSON 路径上的 string/number/boolean/array size。链式调用让成功路径读起来像一句需求，但失败消息必须同时说明 expected 和 actual，避免只抛裸 `AssertionError` 让人重新运行调试。

JSON path 使用点分导航，数组索引也是数字段，例如 `tags.0.name`。数字按源文本比较，`jsonNumberIs("info.version", "1")` 不会把字符串 `"1"` 当成数字。key 自身含点时无法精确寻址，调用者只能退回更宽泛的 body 检查；这个限制比发明转义语法更诚实。package-private `JsonValue` 只服务断言，不是公开 JSON 库，也不应被描述为覆盖完整 JSON 标准的解析器。malformed JSON 抛 `IllegalArgumentException`，说明 fixture/响应本身不成立，不是期望值不匹配；`\u` 转义必须恰好包含四位 ASCII 十六进制数字，截断、非十六进制和全角数字都在同一解析边界被拒绝。数字保留源文本，但仍遵守 JSON 文法：禁止前导零、缺少小数位或指数数字；字符串里的原始控制字符与 JSON 四字符空白之外的 Unicode 空白也不会被悄悄接受。对应词法规则位于 [JsonValue.java:133](../../framework/barezen-testkit/src/main/java/com/barezen/testkit/JsonValue.java#L133) 和 [JsonValue.java:308](../../framework/barezen-testkit/src/main/java/com/barezen/testkit/JsonValue.java#L308)。

第四项设计是 fixtures 每个实例自己持有状态。`textHandler`、`statusHandler`、`echoHandler`、`jsonHandler` 是简单响应构造器；`CountingHandler` 用实例字段 `AtomicInteger` 计数，`PrincipalCapturingHandler` 用 `AtomicReference` 和实例内列表记录最近身份与见过的名字。计数器不能是 static，否则两个测试即使拿到两个 fixture 也共享一次执行。`independentInstances` 测试正是用第二个 fixture 仍为零来拒绝这种实现。

`PrincipalCapturingHandler` 同时实现 `Handler` 与 `SecuredHandler`。它的 plain-handler 入口是 tripwire：错误地把安全 handler 当普通 handler 注册时应直接抛 `AssertionError`，而不是返回一个看似成功的响应。`authenticationFor(token, principal)` 与 `anonymousAuthentication()` 则构造测试用 `Authentication`，让 middleware 的身份、拒绝和计数行为可以通过真实 HTTP 观察。

最后决定数据库 fixture 的边界。`TestDatabase.of(DataSource, resetStatement)` 接收应用已经选好的 DataSource，内部包装 `Database`；`reset()` 只执行调用者给的单条 SQL，`close()` 再 reset 一次。它不关闭 DataSource、不创建驱动、不替应用选择数据库，也不提供额外事务。H2 在 TestKit 中只是 test-scope，用来证明 fixture 对真实数据库有效；生产主代码与架构规则都不得命名 JDBC driver。

这套边界由六条 ArchUnit 规则固定：框架其他模块不得依赖 testkit；testkit 不得依赖 plugins；主代码不得依赖列出的 driver；不得使用 ThreadLocal；不得读取 system property/environment；不得有 static 非 final 字段。编译依赖已经阻止 plugins 导入时，规则仍保留，作为结构和意图的可执行说明。

## 动手

server 的最小实现集中在 [TestServer.java:41](../../framework/barezen-testkit/src/main/java/com/barezen/testkit/TestServer.java#L41)：

```java
this.adapter = new JdkHttpServerAdapter(new InetSocketAddress("127.0.0.1", RANDOM_PORT), router);
this.adapter.start();
this.port = this.adapter.port()
    .orElseThrow(() -> new IllegalStateException("a started adapter must report the port it bound; it did not, so"
        + " the server is running but unreachable and no test can talk to it"));
this.baseUri = "http://127.0.0.1:" + port;
this.sharedClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
```

这段代码没有为测试复制一套 server。adapter 的解析、路由和响应行为与部署时完全相同，因此测试才能真正覆盖第 6 章建立的 HTTP 边界。`close()` 用实例 `volatile boolean closed` 阻止顺序重复 close；它适合 try-with-resources 的常见路径，但不应被夸大为并发 close 的原子保证。忘记关闭会占用端口，测试必须让资源生命周期可见。

以下片段来自 [ReadmeTestKitExampleTest.java:27](../../framework/barezen-testkit/src/test/java/com/barezen/testkit/ReadmeTestKitExampleTest.java#L27)，它覆盖普通路由、认证成功与匿名拒绝：

```java
Router router = Router.builder()
    .route(HttpMethod.GET, "/health", TestFixtures.textHandler("ok"))
    .route(HttpMethod.GET, "/who",
        SecurityMiddleware.wrap(TestFixtures.authenticationFor("t0ken", new Principal("ada")),
            Authorization.allowAll(), TestFixtures.principalCapturingHandler()))
    .build();

try (TestServer server = TestServer.start(router)) {
  TestHttpClient client = server.client();
  Assertions.assertThat(client.get("/health")).statusIs(200).bodyIs("ok");
  Assertions.assertThat(client.request(HttpMethod.GET, "/who")
      .header("Authorization", "Bearer t0ken").send()).statusIs(200).bodyIs("hello, ada");
  Assertions.assertThat(client.get("/who")).statusIs(401);
}
```

这里没有 mock。200、401、响应头与 body 都由真实 adapter 和 middleware 合作产生；匿名请求是否到达 delegate，则由 capturing handler 的计数另行检查。README 示例不是“说明怎么写”的伪代码，测试类逐字执行它，文档漂移会直接让构建失败。

响应快照实现位于 [TestResponse.java:36](../../framework/barezen-testkit/src/main/java/com/barezen/testkit/TestResponse.java#L36)。构造时先复制 body，再用 `TreeMap<>(String.CASE_INSENSITIVE_ORDER)` 重组 header。访问 `header("content-type")` 返回第一个 value；测试不应只调用它来假设只有一个值。需要确认重复 header 行为时，应查看模块公开快照表面及对应集成测试，而不是自行猜测 JDK headers 的合并方式。

OpenAPI 的测试形状同样简单：

```java
TestResponse response = server.client().get("/openapi.json");
Assertions.assertThat(response)
    .statusIs(200)
    .headerIs("Content-Type", "application/json")
    .jsonHas("paths./users.{id}.get")
    .jsonStringIs("info.title", "BareZen Users API");
```

断言只检查文档声明与传输结果；它不会因为 `/users` 出现在 JSON 里就伪造一个 `/users` 请求。工具与 generator 在应用测试里汇合，编译图仍然没有 testkit → plugins 的边。

数据库隔离测试在 [TestDatabaseTest.java:75](../../framework/barezen-testkit/src/test/java/com/barezen/testkit/TestDatabaseTest.java#L75) 写入两行，调用 reset，再确认表已经消失。另一个测试关闭 fixture 后，用同一个 DataSource 建新 fixture，确认旧 schema 不存在。它证明 reset 真的执行，而不只是 `close()` 名字听起来像清理。若 reset 语句只删除部分表，调用者应选择正确的 SQL，TestKit 不会猜数据库结构。

## 常见的第一次写错

1. **错误：用 fake server 直接分发表。** 正确：TestServer 包装真实 JdkHttpServerAdapter。
2. **错误：写死端口。** 正确：绑定 127.0.0.1:0，从 adapter 读取 OS 分配的端口。
3. **错误：客户端自动跟随 redirect。** 正确：固定 NEVER，让 3xx 保持可断言。
4. **错误：忘记 close。** 正确：try-with-resources，让端口和 adapter 线程在失败路径也释放。
5. **错误：把 body 保留为连接上的惰性读取。** 正确：返回已同步读取、可复制的 byte[] 快照。
6. **错误：用 `Map.copyOf` 装 header map。** 正确：保留大小写不敏感 comparator 的不可变 TreeMap。
7. **错误：把 fixture counter 声明为 static。** 正确：每个 fixture 实例独立计数，测试明确验证隔离。
8. **错误：让 TestDatabase 关闭应用 DataSource。** 正确：它只拥有 reset，不拥有连接和驱动。
9. **错误：让 testkit 编译依赖 plugins。** 正确：应用测试同时依赖两者，工具本身只依赖向下契约。
10. **错误：吞掉 InterruptedException。** 正确：包装失败时保留 cause，并恢复中断标志。
11. **错误：把 JSON reader 当完整 JSON 库。** 正确：它只支持当前断言路径，含点号的 key 有明确限制。
12. **错误：宣称并发 close 保证只 stop 一次。** 正确：当前实现只承诺顺序重复 close 幂等。

## 怎么确认这一步没造错

运行：

```bash
mvn -o -B -ntp -pl framework/barezen-testkit -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AssertionsTest,FixturesTest,JsonReaderTest,TestDatabaseTest,TestServerIntegrationTest,ReadmeTestKitExampleTest,BarezenTestKitArchitectureTest,ClassMajorContractTest
```

当前实跑 81 项，全部通过：Assertions 13、Fixtures 14、JsonReader 21、TestDatabase 7、TestServerIntegration 15、ClassMajor 4、Architecture 6、README example 1。这个数字与 ADR-0044 的记录一致，但本章引用的是当前 JDK 24 环境下的再次实跑，而不是照抄历史声明。

`TestServerIntegrationTest` 不只看一个 health 请求。它覆盖七个动词、请求 header 到达 handler、port 与 baseUri 一致、重复 close、ServerSocket 能重新取得刚释放的端口、两个 server 得到不同端口、未路由路径 404、security 的成功/拒绝组合、alice 与 bob 的身份隔离，以及 server 关闭后的请求抛 `TestRequestFailedException`。这些测试分别对应“真实 socket”可能暴露的泄漏与串扰。

`AssertionsTest` 对每种断言同时给出成功与 near-miss。比如 `headerIs` 名称大小写不敏感、值精确匹配；`jsonStringIs("info.version", "1")` 必须拒绝数字 1；`jsonArraySizeIs("tags", 2)` 必须拒绝 3。只测成功路径无法排除一个永远返回 this 的空断言，near-miss 才证明断言拥有失败能力。

还应手工观察两个失败。第一，向关闭后的 server 发请求，异常应是 TestRequestFailedException，而不是 NPE 或裸 IOException。第二，把同一个 CountingHandler 用在两次请求中，再创建一个新 fixture；新 fixture 的 count 必须为 0。前者验证资源边界，后者验证实例隔离。

## 此刻的代码长什么样

TestKit 现在提供一条可复制的消费者测试路径：应用构造 Router，启动真实 adapter，发送 HTTP 请求，读取不可变快照，用框架自己的断言检查结果，最后关闭资源。它没有生产依赖反流，没有 plugin 特例，也没有全局计数器或 ThreadLocal。

它的能力边界也必须随能力一起记住：只包装 JDK adapter，固定 HTTP/1.1；不提供 HTTP/2、WebSocket、SSE、multipart、retry、JUnit extension 或测试排序；JSON reader 不是公开库；client 可以请求绝对 URL，不是沙箱；TestDatabase 不管理 DataSource 生命周期。下一步把这些已证明的局部能力汇入七个可运行示例与整章统一检查点。
