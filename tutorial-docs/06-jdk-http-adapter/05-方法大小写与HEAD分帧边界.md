# 05 方法大小写与 HEAD 分帧边界

## 这一步要解决什么

一个响应“没有 body”可能有三种完全不同的原因：Handler 返回空 `byte[]`；状态码 204 或 304 禁止 body；请求是 HEAD，表示 GET 会发送的 representation 存在，但本次不发送它的字节。把它们都实现为 `sendResponseHeaders(status, -1)` 会得到三种相似却不同的错误字段。本步要用一张 framing matrix 把这些事实固定下来。

方法名还有第二层分裂。HTTP 的标准 method token 大小写敏感，但 BareZen 既有 `HttpMethod.of()` 会把九个标准名称折叠，所以 `head`、`Head`、`HEAD` 都路由到 HEAD Handler。JDK 的 wire 实现却精确比较原始 token 是否等于 `"HEAD"`。如果 dispatcher 使用折叠后的 enum 决定 suppression，适配器与 JDK 会对同一请求作出相反判断。

历史提交把这个问题分两次暴露出来。`fc7f72c` 修复初版 HEAD 完全没有 representation length 的问题；`5822a04` 又发现折叠后的方法判断与 JDK 原始 token 不一致，小写 HEAD 会得到错误长度。本步讲的是修正过程，不是当前代码一次想出了全部规则。

## 先想清楚再写

Response 继续保持 status、Headers、body 三个字段，不增加 OptionalBody、BodyMode 或 StreamingBody。null 与空数组对契约都表示零字节；区别放在状态和方法规则中，而不是让每个 Handler 选择一种内部 body 类型。1xx、204、304 非空 body 在构造时直接拒绝，避免 JDK 静默丢弃应用字节，只在日志里留下 warning。

Handler 也不能自己写 Content-Length 或 Transfer-Encoding。框架已经拥有完整 byte[]，长度是客观事实；若允许 Handler 声明，就可能出现两个 Content-Length、长度与 UTF-8 字节数不符，或空 body 被误标为 chunked。桥应当从真实 body 推导一个长度，而不是把两个事实来源合并。这个规则也适用于自定义 404/500，避免错误路径绕过正常 framing。

普通 200 的规则最直接：body 长度大于 0，声明该长度并写字节；body 长度为 0，写 `Content-Length: 0` 且不写字节。不能把 0 直接传给 JDK 的 sendResponseHeaders，因为那表示 chunked。1xx、204、304 不声明长度、不写 body；这不是“忘记填字段”，而是状态语义。HEAD 对允许 body 的状态则写 representation length，不写 body。

304 的 Content-Length 是 HTTP 允许但当前模型无法表达的情况。HTTP 允许它说明对应 200 representation 有多大，但 Response 禁止 304 携带字节，Handler 也没有单独的 representation length 字段。若从空 body 推导会得到错误 0；若让 Handler 自己填长度，又回到双重事实来源。ADR-0029 因此明确选择 304 无长度，不把它写成待修 bug。未来出现真实 conditional-GET consumer，并且能在同一处理过程从 200 body 得到长度时，才重新讨论。

方法大小写则必须承认两层规则。契约层的 `HttpMethod.of()` 为兼容既有九个方法折叠大小写，Router 以折叠值选 Handler。因此 `head` 和 `Head` 仍会执行 HEAD Handler，Allow 中也只出现标准 HEAD，不会多出小写别名。wire 层不同：只有客户端原始 token 恰好 `"HEAD"`，JDK 才认为是需要抑制 body 的 HEAD；适配器必须比较同一个 raw token。

这意味着当前 `head` 与 `Head` 请求会得到与普通 GET 相同的 body 和真实长度，同时仍由 HEAD Handler 回答。这不是“大小写 HEAD 被错误放过 body”，而是 routing case-folded 与 wire exact-token 的明确组合。测试必须同时检查 Handler 标记和 body 字节，否则只验证调用次数会漏掉 JDK framing 差异，只验证字节又会漏掉路由选错 Handler。

## 动手

- [Response.java:99](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L99)：构造时拒绝 1xx、204、304 的非空 body。
- [Response.java](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java)：拒绝 Handler 自带 Content-Length/Transfer-Encoding，wire framing 由适配器推导。
- [HttpMethod.java:86](../../framework/barezen-web/src/main/java/com/barezen/web/HttpMethod.java#L86)：九个标准方法折叠，扩展 token 保持原样。
- [JdkRequestDispatcher.java:243](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkRequestDispatcher.java#L243)：`!"HEAD".equals(exchange.getRequestMethod())` 决定是否写 body。
- [JdkExchangeBridge.java:205](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L205)：普通 framing、HEAD representation length 与 statusOnly 分支。
- [JdkExchangeBridge.java:243](../../framework/barezen-http-jdk/src/main/java/com/barezen/http/jdk/JdkExchangeBridge.java#L243)：`mayCarryABody` 排除 1xx、204、304。
- [JdkResponseBodySemanticsTest.java:621](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkResponseBodySemanticsTest.java#L621)：八行 framing matrix 的真实 socket 断言。
- [JdkResponseBodySemanticsTest.java:441](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkResponseBodySemanticsTest.java#L441)：精确 HEAD、小写与混合大小写的 body 差异。
- [JdkMethodCaseSemanticsTest.java:155](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkMethodCaseSemanticsTest.java#L155)：契约、路由、dispatch 与 wire 的完整方法矩阵。
- [JdkMethodCaseSemanticsTest.java:392](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkMethodCaseSemanticsTest.java#L392)：HEAD/GET 多种顺序在同一 keep-alive 连接上不解帧。
- [JdkHttpServerConcurrencyTest.java:185](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkHttpServerConcurrencyTest.java#L185)：八种响应形状并发交错，计数证明 Handler 不串请求。
- [JdkRequestBodyLimitTest.java:202](../../framework/barezen-http-jdk/src/test/java/com/barezen/http/jdk/JdkRequestBodyLimitTest.java#L202)：413 HEAD 的 length 17、零 body 字节。
- [ADR-0027](../../docs/adr/0027-http-response-body-semantics.md)、[ADR-0028](../../docs/adr/0028-method-case-and-head-wire-boundary.md)、[ADR-0029](../../docs/adr/0029-response-framing-and-304-length.md)、[ADR-0030](../../docs/adr/0030-response-body-abstraction.md)。

## 常见的第一次写错

把 HEAD 当成“路由到 GET，然后删 body”会跳过独立 HEAD Handler，也无法保证 representation 与 GET 一致。当前 Router 分别注册 GET/HEAD，测试分别计数；适配器只在 wire 层抑制精确 HEAD 的字节。若应用没有注册 HEAD，已知路径会得到 405 和 Allow，而不是偷偷调用 GET。

把空 200、204、304 和 HEAD 都写成同一个 statusOnly 分支，会丢失 HEAD 的长度，也会让 200 empty 误入无长度形状。反过来，对所有状态都写 body.length，会给 204/304 添加非法字段，JDK 可能自己强制改成 -1 并记录 warning。正确矩阵同时约束字段存在性、字段值和后续字节数，三者缺一不可。

只测试大写 HEAD 会错过 `5822a04` 的历史问题。 folded enum 与 raw token 在精确 HEAD 上恰好一致，所以 happy path 全绿；只有发送 `head` 或 `Head` 才能看到适配器与 JDK 判断分叉。方法矩阵因此必须包含大小写、扩展方法、405 Allow、404/500、HTTP/1.0 和 keep-alive 顺序，不能只测三个方法名。

文档也不能继续沿用 ADR-0027 已过时的小写 HEAD limitation。当前源码和 ADR-0028 已修复它。同理，304 无长度是 ADR-0029 的决策，不是尚未实现；响应流式抽象暂缓是 ADR-0030 的范围控制，不是遗漏。把已修正问题、明确限制和未知缺陷分开，教程才能帮助读者判断何时该改代码。

## 怎么确认这一步没造错

先运行响应体语义的 26 项测试：

```bash
mvn -o -B -ntp -pl framework/barezen-http-jdk -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=JdkResponseBodySemanticsTest
```

它的 FramingMatrix 一次 socket run 检查八行：200 empty、200 non-empty、204、304、HEAD 200、404、405、500。每行都比较 status、Content-Length 和实际 body，并额外断言没有 Transfer-Encoding。期望长度分别为 0、5、无、无、5、9、18、21；404 正文 `Not Found`，405 正文 `Method Not Allowed`，500 正文 `Internal Server Error`。

嵌套测试还覆盖 204/304 非空 body 构造拒绝、真实 HttpClient keep-alive、HEAD 空 representation 的 0、HEAD 204/304 无长度、HEAD 404/500 的长度、HEAD 多字段与两个 Set-Cookie、1xx 状态行、413 由 RequestBodyLimitTest 单独覆盖。NoServerSideCorrection 捕获 `com.sun.net.httpserver` 的 WARNING，证明适配器没有把需要 JDK 纠正的 framing 交出去。

再运行方法大小写类：

```bash
mvn -o -B -ntp -pl framework/barezen-http-jdk -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=JdkMethodCaseSemanticsTest
```

当前为 15 项。2026-11-27 在 Java 24.0.2 上 15 项全部通过。带控制字符、引号或非 ASCII 的 method token 由 JDK parser 或 `HttpMethod.of` 拒绝为 400，不再进入路由的 405 分支；阅读方法矩阵时以 ContractLevel、WireLevel、KeepAliveOrders 和 HeadShapes 的证据为准。

并发形状测试用 8 线程、50 轮，每轮检查九项 wire 结果，包括 200 body、200 empty、204、304、HEAD、GET、405、404 和 OPTIONS *。parametrizedCalls、getCalls、headCalls 分别精确计数，证明不同状态、body 和 framing 没有跨请求共享。单请求全绿不能证明这一点，因为共享字段往往只在交错时暴露。

反向验证应针对两个历史 bug。把 HEAD 的 representation length 删除，HEAD 字段测试应立即失败；把 `writesBody` 改成基于 `HttpMethod.HEAD`，小写 HEAD 的 body 与长度应偏离 JDK；给 204 写 0，framing matrix 与真实客户端测试应失败；给 304 写 body 长度，契约拒绝或 matrix 应失败。每一条断言都必须有真实来源，不能只检查“响应看起来为空”。

## 此刻的代码长什么样

本步交付一张八行 framing matrix、一个原始 token 的 HEAD 判断，以及契约、路由、dispatch、wire 四层的方法大小写证据。精确 HEAD 得到 representation length 和零 body；`head`/`Head` 执行 HEAD Handler 但发送普通 body；204/304 无长度，304 的限制有明确 ADR 依据。

同时交付 26 项响应体测试、15 项方法测试全部通过，以及并发形状和 413 HEAD 覆盖。JDK parser 与契约层各自拒绝非法 token 的边界保留在 InvalidRequestLines 中，不伪装成框架 bug，也不省略。下一章步骤将把本章所有测试组织成可复现的验收命令与失败定位表。
