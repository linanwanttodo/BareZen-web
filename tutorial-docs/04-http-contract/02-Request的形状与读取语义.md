# 02 Request 的形状与读取语义

## 这一步要解决什么

上一章确定 Handler 不接触服务器，下一步必须决定 Request 到底保留多少信息、以什么形式保留。若只保留 `URI`，服务器解析时可能已经把 `%2F` 解码成路径分隔符；若直接提供 query 参数 Map，框架又要替所有应用决定重复键、加号、字符集和分号规则。

本章只携带五类事实：method、path、query、Headers 和 body。path/query 保持请求目标中的原始文本，body 是完整的内存字节副本。Request 不负责路由、解码、参数解析或流关闭，它只是应用能够信任的一份不可变输入。

## 涉及的核心文件与变更

- [Request.java:72](../../framework/barezen-web/src/main/java/com/barezen/web/Request.java#L72)：唯一构造器，校验 method/path/headers，复制 body。
- [Request.java:99](../../framework/barezen-web/src/main/java/com/barezen/web/Request.java#L99)：path 原始读取。
- [Request.java:112](../../framework/barezen-web/src/main/java/com/barezen/web/Request.java#L112)：query 返回 Optional。
- [Request.java:136](../../framework/barezen-web/src/main/java/com/barezen/web/Request.java#L136)：body 每次返回副本。
- [HttpMethod.java:86](../../framework/barezen-web/src/main/java/com/barezen/web/HttpMethod.java#L86)：标准方法忽略大小写归一，其他方法保持原始 token。
- [RequestTest.java:21](../../framework/barezen-web/src/test/java/com/barezen/web/RequestTest.java#L21)：表面、路径、查询、字节体、拒绝和日志脱敏测试。
- [HttpMethodTest.java](../../framework/barezen-web/src/test/java/com/barezen/web/HttpMethodTest.java)：标准方法、未知方法与拒绝输入。
- [ADR-0018 §6–§8](../../docs/adr/0018-http-is-a-contract-not-a-server.md#L238)：同步 Handler、原始值、字节体与防御性复制。

`32ebc41` 建立 Request 时已经写入“无 uri、无 param、无 decoded twin”。`74ea0fb` 增加 OPTIONS * 的真实适配器测试后，Router 对星号目标做了小改；Request 本身接受 `*` 的规则来自请求目标语义，不是给某个服务器打补丁。

## 为什么这样设计

构造器只有五个参数，读取器也只有五个事实加 toString。没有 scheme、remote address、protocol version、cookie 或 attribute，因为这些值或者属于 socket，或者需要字段级解析规则。以后真实消费者需要远端地址时，应由适配器以明确类型提供，而不是提前塞进所有 Request。

path 保存客户端发送的原始形式。`/a%2Fb` 与 `/a/b` 在语义上不是同一个资源形状：前者包含一个编码斜杠，后者是两个段。`java.net.URI.getPath()` 会把两者都返回为 `/a/b`，因此 [keepsPercentEncodingIntact](../../framework/barezen-web/src/test/java/com/barezen/web/RequestTest.java#L71) 同时断言 Request 保留编码和 URI 解码后的差别。解码是资源所有者的事情，载体不能替它决定。

原始还意味着不归一化。`/a//b`、`/a/`、`/a/./b` 都原样保存。尾斜杠、重复斜杠和点段是否等价，是第五章 Router 要显式处理的匹配规则。Request 如果先清理一遍，Router 的规则就不再是对客户端请求目标的规则，而是对框架重写结果的规则。

path 必须以 `/` 开头，或者恰好是 `*`。`*` 支持 `OPTIONS *` 询问服务器本身；普通相对路径和绝对形式 URI 被拒绝，因为它们的解析依赖契约没有的 base 或代理上下文。拒绝发生在构造时，错误消息直接说明缺的是 request target，而不是等到 Handler 里再猜。

query 与 path 一样保持原始，不带前导 `?`。`/search` 对应 Optional.empty()，`/search?` 对应 Optional.of("")。二者不同：“没有提供过滤条件”与“显式提供空过滤条件”可能触发默认和重置两种行为。用 Optional 表达预期缺失，比返回 null 或默认空串更明确，也和 Headers.first 的规则一致。

query 不提供参数 Map，因为 `q=a%20b+&x=1&x=2` 已经包含多个待决问题：`+` 是否等于空格，重复 x 保留哪个还是全部，分号是否分隔，非法百分号如何处理。框架现在回答任何一个，都会成为所有应用无法覆盖的默认政策。正确做法是等第一个消费者写一个小解析器，并把规则写清。

method 使用 HttpMethod 而非 enum 或 String。九个标准方法有共享常量，`of("get")` 返回 GET；未知方法保留原文，`of("purge")` 与 `of("PURGE")` 不相等，因为 HTTP 方法 token 区分大小写。String 比较容易写错 `"Get"`，enum 又无法表达 WebDAV 或缓存服务器的扩展方法。

这条标准方法归一化只发生在 HttpMethod 的值构造中，不意味着可以改写 Request 的原始目标，也不替代适配器对真实请求行的解析。契约测试直接调用 `of`，适配器测试再验证服务器实际送来的 token；两层分别守住值语义与传输边界，避免把一个服务器的大小写行为误当成 HTTP 通则。

body 采用 byte[]，构造和读取都复制。适配器可以复用读缓冲，Handler 可以复用输出缓冲，任何一方事后修改都不能改变 Request。空 body 永远返回非 null 的零长度数组，null 输入与零长度输入在契约里统一；线上是否有 `Content-Length: 0` 由适配器决定。

内存 body 的代价必须写清：大上传会被完整缓冲，无限缓冲会造成拒绝服务。上限属于适配器，因为它知道读取成本和部署策略；Request 作为通用值对象不能替所有应用拍一个数字。流式 body 需要额外定义关闭、取消和跨线程生命周期，不能作为当前 byte[] 的一个 boolean 开关。

## 怎么验证

[RequestTest.Surface](../../framework/barezen-web/src/test/java/com/barezen/web/RequestTest.java#L21) 用反射断言只有 method/path/query/headers/body/toString，没有 setter、put、remove。这个测试保护的不是代码风格，而是 Handler 能否信任输入不被后一个组件改写。

路径与 query 测试分别覆盖 `%2F`、编码点段、重复斜杠、尾斜杠、OPTIONS *、相对路径拒绝、空 query 与未解析 query。字节体测试覆盖输入缓冲被改写、返回数组被改写、null/空数组和二进制内容。日志测试构造 Authorization 与密码 body，断言 toString 只有方法、path 和字节数。

```bash
mvn -o -B -ntp -pl framework/barezen-web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RequestTest,HttpMethodTest
```

当前 RequestTest 有 19 项、HttpMethodTest 有 11 项。完整 web 模块检查点会再次运行它们，并同时验证 Router 与架构规则。

## 常见错误与失败后果

把 path 存成 URI 再调用 getPath，会让 `%2F` 提前变成段分隔符，Router 可能把客户端没有请求的资源匹配出来。把 query 空串当缺失，会让“重置过滤”误走默认分支。把未知方法折叠成 GET 或 UNKNOWN，要么执行错误操作，要么丢失诊断所需的原始 token。

让 body() 返回内部数组，会让一个 Handler 修改另一个 Handler 正在读的请求；只复制构造、不复制读取同样不够。直接把 InputStream 放进 Request，又会引出谁关闭、未读完怎么办、装饰器调用两次怎么办等服务器相关问题。当前边界用完整副本避开这些问题，同时用适配器上限约束内存成本。

## 本步交付物

Request 现在是一份可信、不可变、保持原始编码的输入快照。应用知道请求方法和目标，却不会被框架强行解释资源语义。下一步定义另一半：Handler 如何返回状态、字段和字节，以及为什么“返回答案”比“直接写 socket”更利于组合。
