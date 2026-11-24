# 02 JdkExchangeBridge 的职责边界

## 这一步要解决什么

服务器已经能创建 exchange，但 exchange 不是 BareZen 的 Request，Handler 返回的 Response 也不是可以直接交给 `sendResponseHeaders` 的三个参数。中间需要一座桥：进入方向把 JDK 的 method、URI、Headers 和输入流翻译成契约；离开方向把状态、字段行和字节翻译成 HTTP framing。桥的两侧类型不同，但必须保持同一请求的语义，不能在翻译时“顺手优化”。

这座桥不能拥有路由。它不知道 `/users/{id}` 应该命中哪个 Handler，也不维护 404/405 的业务政策；它只提供几个最小错误 Response 供 dispatcher 选择。桥也不能拥有服务器生命周期，不创建 exchange，不关闭线程池。`JdkExchangeBridge` 是包内 final、只有 static 方法的转换单元，正是为了把“翻译”与“分派”和“资源所有权”分开。

进入方向最重要的约束是原始 target。URI 的 raw path 与 raw query 保留百分号编码和 `+`，Router 第五章已经按这些原始段工作；如果 bridge 先解码再构造 Request，之前所有关于 `%2F`、尾斜杠和字面量大小写的测试都会在真实服务器上失真。离开方向最重要的约束是 framing：body 长度、状态是否允许 body、请求方法是否抑制 body，是三个需要分别判断的事实。

## 涉及的核心文件与变更

- [JdkExchangeBridge.java:81](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L81)：`toRequest(HttpExchange, BodyLimit)` 构造 Request。
- [JdkExchangeBridge.java:122](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L122)：解析单个 Content-Length，异常或重复值不作为可信声明。
- [JdkExchangeBridge.java:147](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L147)：JDK Headers 转成 BareZen Headers。
- [JdkExchangeBridge.java:205](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L205)：`send(...)` 完成字段复制、HEAD 长度和状态写出。
- [JdkExchangeBridge.java:243](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L243)：判断状态是否允许 body。
- [JdkExchangeBridge.java:265](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L265)：最小 404、405、400、500、413 工厂。
- [JdkRequestDispatcher.java:111](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkRequestDispatcher.java#L111)：dispatcher 的最外层 handle 与 finally close。
- [JdkRequestDispatcher.java:169](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkRequestDispatcher.java#L169)：match 与 methodsFor 决定 404/405，Handler 异常决定 500。
- [JdkHeaderAndBodyTest.java:96](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHeaderAndBodyTest.java#L96)：重复请求字段按值顺序到达。
- [JdkHttpServerAdapterEndToEndTest.java:154](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerAdapterEndToEndTest.java#L154)：真实 socket 保留 `%2F`。
- [JdkErrorSemanticsTest.java:53](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkErrorSemanticsTest.java#L53)：404 与 405 的 wire 证据。
- [ADR-0022](../../docs/adr/0022-exchange-request-response-bridge.md) 与 [ADR-0023](../../docs/adr/0023-http-error-semantics.md)：翻译规则与错误归属。

`a2e05fa` 的 bridge 已有原始 target、Headers 转换和基本 framing；后续 BodyLimit 给 `toRequest` 增加参数，HEAD 修复改变 `send`，TLS 则不改变 bridge。阅读提交差异时，应把“桥的基本形状很早稳定”和“边界规则后来被测试修正”分开。

## 为什么这样设计

进入方向先取 `exchange.getRequestMethod()`，再用 `HttpMethod.of(...)` 折叠九个标准方法；URI 则分别取 `getRawPath()` 与 `getRawQuery()`。path 不允许 null，因为 Request 的 path 必须以 `/` 开头或等于 `*`；query 可以 null，因为 Optional 要区分“没有 `?`”与“有 `?` 但为空”。直接把 query 变成空字符串会抹掉这个区别，也会让 Handler 对原始请求目标作出错误判断。

Headers 转换必须承认 JDK 的事实：请求字段名可能被 canonicalize，到达顺序不保留，但同名多个值及其顺序仍可读取。BareZen Headers 提供大小写不敏感匹配和逐值保存，因此 bridge 不需要伪造原始拼写。它也不能把重复值用逗号拼起来，尤其 `Set-Cookie` 在响应方向必须逐行 add；否则应用看到的 cookie 数量会改变。

请求体读取前先看可信的 declared Content-Length。若单个值能解析且已经超过 limit，bridge 在触碰输入流之前抛 `BodyLimit.Exceeded`。这能阻止“声明 10 GiB，实际只发送 1 KiB”迫使服务器等待或分配巨大 buffer。重复、无法解析的值返回 -1，表示没有可信长度，随后由有界读取和 JDK parser 处理；bridge 不自己实现完整 HTTP parser。

离开方向不能把 `body.length` 原样传给 JDK。JDK 的 `sendResponseHeaders` 对 length 0 使用 chunked 语义，而 BareZen 的空 200 应明确是 `Content-Length: 0`；所以空 body 通常传 -1，让 JDK 写零长度，不启用 Transfer-Encoding。正长度 body 传实际字节数。1xx、204、304 不允许 body，因此不声明长度。

HEAD 更不能简单“少写 body”。Handler 返回的 `byte[]` 表示 GET 会发送的 representation，bridge 需要把它的长度写进响应字段，再用 -1 告诉 JDK 不发送 body。是否抑制不能看折叠后的 `HttpMethod.HEAD`，因为 JDK 自己比较原始 token；这一步由 dispatcher 的 `writesBody(exchange)` 传入准确布尔值，第五章会展开。

错误响应也走同一个 `send`。404 的 `Not Found`、405 的 `Method Not Allowed`、500 的 `Internal Server Error` 都是普通 byte[]，因此 HEAD 请求同样得到 representation length 而不发送字节。bridge 不把异常 message、请求 path 或 body limit 拼进错误正文，避免把内部细节反射给客户端。

## 怎么验证

Header/Body 与端到端测试分别覆盖桥的两侧：

```bash
mvn -o -B -ntp -pl framework/barezen-http-jdk -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=JdkHeaderAndBodyTest,JdkHttpServerAdapterEndToEndTest,JdkErrorSemanticsTest
```

这三类当前合计 51 项。Header/Body 的 17 项检查请求字段、响应字段、重复 Set-Cookie、UTF-8 字节长度、空 body 和 70000 字节完整写出；EndToEnd 的 12 项把 raw target 送到真实 socket；ErrorSemantics 的 22 项固定 404、405、400、500 以及 JDK parser 和 committed response 的边界。

请求侧可以重点观察三个测试。`everyValueOfARepeatedFieldArrivesInOrder` 证明同名字段值不丢失；`anEmptyValueIsPresentAndDistinctFromAnAbsentField` 证明空值与缺失没有合并；`theOrderTheFieldsArrivedInIsNotTheOrderTheHandlerSeesThemIn` 则把 JDK 的已测事实写成明确边界，而不是假装保留了 wire 顺序。

原始 target 可以发送 `/users/a%2Fb`，它必须作为一段到达参数路由；`/users/%2e%2e/admin` 必须仍有两个可捕获段；`/users/a+b` 不能变成 `a b`；`/users/%41BC` 不能命中字面量 `/users/ABC`。这些断言必须用 `RawHttp`，因为高级客户端可能先规范化或解码 target，测试到不了 bridge 的输入。

响应侧必须同时检查字段数量与 body。`twoSetCookieValuesLeaveAsTwoLines` 防止折叠；`anEmptyBodyIsDeclaredAsZeroBytesAndNotChunked` 防止 JDK 的 length 0 分支；`aHeadResponseHasFieldsAndTheRepresentationLengthButNoBody` 防止把 HEAD 当成普通空响应。只断言 `body().isEmpty()` 会同时放过“正确 HEAD”和“错误 204”，所以字段行不可省略。

反向验证可分别污染入口与出口：把 `getRawPath()` 改成 `getPath()`，编码路径测试应失败；把响应重复字段改成覆盖，Set-Cookie 测试应失败；把空 body 的 declared 从 -1 改成 0，测试应看到 chunked；在 404 正文加入 path，错误语义测试应失败。桥没有独立单元测试并不妨碍这些真实 socket 测试，因为 `HttpExchange` 不能轻易伪造，而最终契约本来就发生在 wire 上。

## 常见错误与失败后果

把 URI 的 decoded path 交给 Router 是最隐蔽的错误：普通 URL 看似正常，只有编码斜杠或点段请求会选错资源。若应用据此读文件，还可能形成路径边界误判。正确做法不是“解码后再 normalize”，而是保留 raw target，把解码留给确实需要语义值的消费者，并明确它在哪一层发生。

把请求 Headers 当作 Map<String, String> 会静默丢重复值；把响应 Headers 全部合成逗号分隔的一行又会破坏 Set-Cookie。只按大小写不敏感查找、不保存每个值，无法表达 HTTP 的多字段模型。Name canonicalize 本身不是 bug，但若文档声称保留客户端拼写，就会与真实行为冲突。

错误归属也常被混淆。畸形请求行可能在 exchange 前就被 JDK 拒绝，adapter 无法改写它的 HTML 400；进入 bridge 后契约拒绝 target 或读取失败，才是 adapter 的纯文本 400；Handler 抛 RuntimeException 才是 500。若 catch 所有 Throwable，连 Error 也翻译成 500，会掩盖 JVM 级问题；若响应已经提交后再 catch IOException 并尝试写第二个状态，HTTP 连接会出现无法定义的第二回复。

最后，不要为了错误页“更友好”而返回 JSON envelope 或堆栈。当前政策是短文本、无敏感细节、状态长度可由字节推导。模板、国际化或统一错误 DTO 都可以是未来应用层能力，但不应先塞进 bridge。桥的可测试性来自规则少而明确，不来自错误页复杂。

## 本步交付物

本步交付包内 final 的 `JdkExchangeBridge`：Request 转换保留 raw path/query、Headers 多值和 body 字节；Response 转换逐值写字段、按状态和 HEAD 推导 framing；五个最小错误响应复用同一写出路径。dispatcher 负责选择，bridge 负责翻译，exchange 最终由 dispatcher 关闭。

同时交付 51 项 Header/Body、EndToEnd 与 ErrorSemantics 测试。它们证明真实字节往返、编码目标存活、重复字段不折叠和错误不泄露，但没有证明请求体上限、完整生命周期和所有方法大小写组合；后三步会继续补齐这些缺口。
