# 03 Response 的形状与写出语义

## 这一步要解决什么

Request 解决“应用读到什么”，Response 要解决“应用拿什么回答”。如果 Response 像 Servlet 那样持有输出流，Handler 可以在任何时刻写状态、写一半 body 再抛异常；外层认证、日志或指标组件即使拿到了 Response，也无法改变已经上线的答案。

本章把响应定义成一次性生成的不可变值：int status、Headers headers、byte[] body。Handler 返回它，之后由装饰器读取并生成新值，最后由适配器写 socket。这样“业务处理”和“传输提交”始终是两个时刻，错误也不会留下半份已经发出的 HTTP 响应。

## 先想清楚再写

Response 只有 status、headers、body 三个事实，没有 ok()、json()、redirect() 或 notFound()。这些 helper 看似减少重复，实际会把 Content-Type、body 格式和状态约定写进所有应用无法拒绝的公共 API。应用可以自己定义 text(json) 或 problem(status)，但那是应用 convention，不是 HTTP 载体的职责。

状态使用 int，限定 100–599，而不是枚举。HTTP 允许 418、451 和私有 5xx，枚举列表会随着真实需求不断扩张；但 99 或 600 无法写在状态行上，所以构造时必须拒绝。[acceptsEveryRangeTheSpecificationDefines](../../framework/barezen-web/src/test/java/com/barezen/web/ResponseTest.java#L50) 同时覆盖合法边界与非法数字，避免只测 200。

body 与 Request 一样是 byte[]，不应用 charset。JSON、图片、压缩包都只是字节，Content-Type 的解析与字符编码由应用或字段级组件负责。null 与零长度数组统一为空 body；当前契约不保留“刻意没有 body”和“刻意空 body”的第三种状态，因为适配器最终都要决定 wire framing。

不可变来自构造和读取双向复制。Handler 返回后可能复用临时缓冲，适配器读取时也可能把数组交给另一个组件；只要任何一方拿到内部引用，就能改写已经生成的 Response。复制成本是明确的，换来的是所有装饰器看到同一份答案，以及“返回后不再改变”的可信保证。

返回模型让装饰器只靠 Handler → Handler 就能存在：

```java
private Handler timing(Handler inner) {
  return (request, parameters) -> {
    Response response = inner.handle(request, parameters);
    return new Response(
        response.status(), response.headers().with("X-Handler", "wrapped"), response.body());
  };
}
```

这段代码来自 [HandlerTest.java:289](../../framework/barezen-web/src/test/java/com/barezen/web/HandlerTest.java#L289)。内层先产生完整答案，外层再读取状态、字段和 body，生成一个新 Response。若内层已经写出 socket，外层加 X-Handler 就太晚了。短路认证同样简单：不调用 inner，直接返回 401。

异常语义也由返回模型决定。Handler 抛出的 RuntimeException 或 Error 原样离开，契约不捕获、不包装、不记录。[letsAHandlerFailureEscapeUntouched](../../framework/barezen-web/src/test/java/com/barezen/web/HandlerTest.java#L219) 断言调用方拿到的就是同一个异常实例；[letsAHandlerTranslateItsOwnFailure](../../framework/barezen-web/src/test/java/com/barezen/web/HandlerTest.java#L256) 又证明 Handler 可以自己把 IOException 转成 500。两种都合法。

没有返回 Response 就是没有发出应用答案。业务副作用可能已经发生，例如数据库写入或后台任务提交，但状态行和字段不会先上线。这不等同于事务回滚，只是把“传输提交”从 Handler 内部拿走。适配器之后可以选择错误状态、错误格式和日志政策。

内存响应体仍是明确限制。大文件或生成导出必须完整进入 byte[]，且构造和读取会复制；流式响应需要关闭、超时、取消和线程生命周期，不能靠在 Response 上加一个 stream 布尔值实现。当前设计接受复制成本，换取无需服务器即可测试和组合的确定语义。

## 动手

- [Response.java:100](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L100)：构造时校验状态、headers 与 body，并复制字节。
- [Response.java:115](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L115)：三个读取器，没有 send/write/setStatus。
- [Response.java:156](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L156)：状态限制为 100–599。
- [Response.java:180](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L180)：1xx、204、304 禁止非空 body。
- [Response.java:205](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L205)：Content-Length 与 Transfer-Encoding 不属于 Handler。
- [ResponseTest.java:20](../../framework/barezen-web/src/test/java/com/barezen/web/ResponseTest.java#L20)：表面、状态、body、复制与拒绝测试。
- [HandlerTest.java:284](../../framework/barezen-web/src/test/java/com/barezen/web/HandlerTest.java#L284)：不依赖 Middleware 类型的装饰器。
- [ADR-0018 §3](../../docs/adr/0018-http-is-a-contract-not-a-server.md#L140) 与 [ADR-0027](../../docs/adr/0027-http-response-body-semantics.md)：返回模型与后续分帧约束。

`32ebc41` 的初始 Response 只有 115 行；`ef1f5db` 根据真实 JDK 行为补上无体状态与分帧字段拒绝，ResponseTest 也增加 154 行。这个顺序很有教育意义：先确定“返回而不是写出”，再用服务器实测找出返回模型仍必须拒绝的非法形状。

`ef1f5db` 不是单纯补校验函数，而是把“哪些形状根本无法被当前适配器忠实写出”带回契约。204 带 body 会被 JDK 丢弃，Handler 声明的 Content-Length 可能让 HttpClient 放弃响应，HEAD 与 304 又可能出现重复长度。若这些全部留给适配器静默纠正，应用会看到一个 Response，客户端却收到另一个答案。构造期拒绝把矛盾暴露在写下 Response 的代码旁边，也比在日志里寻找服务器警告更接近根因。

当前源码还把 1xx 整个家族、204 和 304 列入无体状态。这里拒绝的是非空 body，不是拒绝状态本身；空数组与 null 仍然可用。framing 校验则按字段名称拒绝，即使值为空、重复值一致或大小写不同也不能通过，因为契约不负责证明一组传输字段是否自洽。具体 wire 矩阵在下一步结合 Headers 和 ADR-0029 展开。

## 常见的第一次写错

给 Response 加 OutputStream，会让异常发生后留下无法撤回的半响应。给 status 提供 setter，会让后一个组件看到与前一个组件不同的状态，测试只能在调用链结束时猜最终值。提供 json() helper 会把某一种序列化库和错误 envelope 固定到契约，违背驱动无关目标。

把 body 直接保存调用方数组，会让 Handler 的复用缓冲在返回后改写响应；只复制输入、不复制输出，则适配器或装饰器仍能改写。让契约 catch 所有异常并转 500，又会替应用选择错误格式和日志政策，还可能把 StackOverflowError 这类严重故障伪装成普通业务失败。

## 怎么确认这一步没造错

表面测试反射检查 Response 的公开方法只有 status、headers、body、toString，特别排除 send、write、body(OutputStream) 和 setStatus。状态测试覆盖 100/599 与 99/600；body 测试覆盖 null、零长度、二进制、输入数组和输出数组的隔离；toString 测试确认错误 body 与 WWW-Authenticate 值不会进入日志摘要。

组合测试调用 timing(inner)，不仅检查 X-Handler 存在，还检查原状态和 body 保持不变。短路测试不调用内层即可返回 401，证明装饰器不需要 next 对象或 chain 注册表。

```bash
mvn -o -B -ntp -pl framework/barezen-web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ResponseTest,HandlerTest
```

当前 ResponseTest 26 项、HandlerTest 24 项。它们验证 Response 的构造边界和 Handler 的组合方式，不验证真实 socket 分帧；后者要等第六章适配器检查点。

## 此刻的代码长什么样

Response 现在是完整、不可变、可组合的应用答案。它还不知道 HEAD/304 最终写不写字节，也不知道 Content-Length 是多少，这些属于写出层。下一步把 Headers 的重复值、大小写、控制字符与无体状态放在一起，补齐构造时必须拒绝的 HTTP 边界。
