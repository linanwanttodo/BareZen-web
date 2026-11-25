# HTTP 契约、路由与 Handler

## 这份文档讲什么

BareZen 把“一个 HTTP 请求是什么”与“由哪个服务器接收它”分开。应用依赖 `barezen-web` 中的契约类型编写路由和 Handler；`barezen-http-jdk` 才把 JDK 的 `HttpExchange` 转换为这些类型，并把 Handler 返回的 `Response` 写回 socket。理解这条边界，才能解释为什么有些规则属于契约，有些错误由适配器回答。

本文使用当前源码中的最终 API。尤其要注意：早期 [ADR-0018](../adr/0018-http-is-a-contract-not-a-server.md) 记录过单参数 Handler 形态，但 [ADR-0020](../adr/0020-route-parameters-contract.md) 已正式将它修订为两参数；不要按早期决策中的签名编写应用。

## 1. 注册一个 Handler

当前接口只有一个方法：

```java
@FunctionalInterface
public interface Handler {
    Response handle(Request request, Map<String, String> parameters);
}
```

两个输入分别承担不同职责：`Request` 描述整个请求，`parameters` 只保存从路由模式中捕获的路径参数。Handler 同步执行并返回一个完整的 `Response`，不捕获异常，也不应把每次请求的数据保存在实例字段中。

最直接的注册方式是构造路由表：

```java
Router router = Router.builder()
        .route(HttpMethod.GET, "/books", (request, parameters) ->
                text(200, "the library has no books yet"))
        .route(HttpMethod.GET, "/books/{id}", (request, parameters) ->
                text(200, "book " + parameters.get("id")))
        .route(HttpMethod.POST, "/books", (request, parameters) ->
                text(201, "created"))
        .build();
```

这段代码来自 [RoutingExample.java](../../examples/src/main/java/com/barezen/examples/routing/RoutingExample.java)，其中 `text` 是示例自己的辅助方法，用 UTF-8 字节和 `text/plain; charset=utf-8` 构造 `Response`。它不是框架 API。

`Router.Builder.route(HttpMethod, String, Handler)` 在注册时校验方法、模式和 Handler，并拒绝同一个 method + pattern shape 的重复注册。`build()` 产生不可变路由表；之后继续修改原 Builder，不会改变已经构建的 `Router`。应用也可以使用 `Builder.contributing(Iterable<? extends RouteContributor>)` 组合插件贡献的路由，但插件发现不是使用 Handler 的前提。

## 2. 路由匹配保持原始路径

`Router.match(Request)` 返回 `Optional<RouteMatch>`。路由模式按 `/` 分段比较，比较对象是 `Request.path()` 中未经解码的字符串。

一条 `{name}` 参数段必须恰好由左右花括号和一个参数名组成，并匹配一个非空的原始段。参数可以出现在任意位置；捕获时按模式中声明参数的顺序放进 `Map`，不按名字与路径索引直接配对。

```java
RouteMatch match = router.match(request).orElseThrow();
String id = match.parameters().get("id");
```

`RouteMatch` 是不可变 record，包含 `handler()` 与 `parameters()`。参数 map 非空、不可变，键来自模式声明，值保持请求中的原始文本。框架不负责把值转换成数字，也不负责 URL 解码；需要业务转换时，由 Handler 明确处理。

匹配规则如下：

- `/books` 只匹配一段 `/books`，不会前缀匹配 `/books/7`。
- `/books/{id}` 只匹配恰好两段，`id` 不能是空段。
- 字面量区分大小写；`/Books` 与 `/books` 不是同一路径。
- 尾斜杠有意义：`/books/` 有两段，最后一段为空，不等于 `/books`。
- `%2F` 是两个百分号字符加字母 `F`，不会被拆成一个额外路径段；`%2e%2e`、`+` 也不会在这里被规范化或解码。
- 没有 wildcard、正则、可选段、后缀匹配或路径归一化。

同一个路径形状下，字面量路由优先于参数路由。例如 `/books/new` 由 `/books/{id}` 与字面量 `/books/new` 共同覆盖时，请求走字面量路由。这个优先级不取决于注册先后；真正相同的 method + shape 则直接拒绝注册。

`match("*")` 始终为空，`methodsFor("*")` 返回空集合。`OPTIONS *` 不是 BareZen 路由表中的普通资源请求；当前 JDK 适配器会在 BareZen 收到它之前回答 404，详见 [ADR-0026](../adr/0026-options-star-semantics.md)。

## 3. 区分“没有路由”与“方法不对”

`Router` 只负责匹配，不调用 Handler，也不生成 404 或 405。它提供两个正交问题：

```java
Optional<RouteMatch> found = router.match(request);
Set<HttpMethod> methods = router.methodsFor(request.path());
```

- `found` 有值：路径和方法都能匹配。
- `found` 为空、`methods` 非空：这个路径存在，但当前方法不支持。
- 两者都为空：没有路由覆盖这个路径。

`methodsFor` 保留注册顺序并返回不可变集合。适配器负责把上述结果变成 HTTP 响应；不要在路由层再手写一套 404/405，否则路由匹配与错误语义会出现两个事实来源。

## 4. 读取请求

[Request.java](../../framework/barezen-web/src/main/java/com/barezen/web/Request.java) 是一个完整、不可变的值：

```java
public Request(HttpMethod method, String path, String query,
               Headers headers, byte[] body)

public HttpMethod method()
public String path()
public Optional<String> query()
public Headers headers()
public byte[] body()
```

各字段的语义如下。

### method：方法值，不是枚举

`HttpMethod` 是值类型。九个标准方法名——`GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`CONNECT`、`OPTIONS`、`TRACE`、`PATCH`——忽略大小写并复用共享值；扩展方法保留原始拼写。`HttpMethod.of("get")` 的 `text()` 是 `GET`，但扩展方法不会因此被擅自转成大写。

路由匹配使用这个归一化后的值。实际 wire 上是否抑制 HEAD body，则是适配器根据原始请求 token 决定的事情，不能把这两层语义混为一谈。

### path：请求目标的路径部分

构造 `Request` 时，路径必须由 `/` 开头，或者恰好是 `*`。`path()` 返回原始路径，不做解码、不做 `.`/`..` 消除、不合并重复斜杠。需要安全地处理文件或业务键时，应在明确的业务边界再做验证，而不是假设路由器已经清洗过路径。

### query：区分无 query 与空 query

`query()` 返回 `Optional<String>`，而不是一个已经解析的 map。构造时允许 `null`；无 query 的 `/search` 得到 `Optional.empty()`，带空 query 的 `/search?` 得到 `Optional.of("")`。

框架不拆分 `&`、`=`，也不把 `+` 转成空格。应用若需要表单或查询参数，应选择并记录自己的解析规则。不要把原始 query 字符串直接当作可信的键值来源；非法百分号转义应在应用输入边界返回 400，不能让它以 RuntimeException 变成 500。

### headers：可重复的字段集合

`Headers` 不可变，名称匹配忽略大小写，但保留首次写入时的拼写和字段顺序。常用 API 是：

```java
Headers.empty()
Headers.of("Content-Type", "text/plain; charset=utf-8")
headers.with("Set-Cookie", "a=1")
headers.first("content-type")
headers.values("set-cookie")
headers.names()
```

`of` 创建一个字段，`with` 追加而不是替换。因此同名字段可以保留多个值，适合 `Set-Cookie` 等场景；`first` 只返回第一个值，`values` 返回全部值。字段不存在与字段存在但值为空字符串是两种不同情况。

字段名必须是 HTTP token，字段值不得包含控制字符。`Headers.toString()` 只显示名称，不显示值，避免日志无意中泄露 cookie 或凭据；需要记录值时仍应显式脱敏。

### body：完整字节数组

`body()` 返回完整请求体的副本；没有 body 时返回空数组。构造 `Request` 时也会复制传入数组，调用方之后修改原数组不会改变请求值。

契约不提供流式读取。当前 JDK 适配器使用有界读取，默认上限为 1 MiB，配置键是 `barezen.http.jdk.maxRequestBodyBytes`；超限请求在进入 Handler 前得到 413。大文件上传不应直接把 `Request.body()` 当作通用解决方案。

## 5. 返回响应

`Response` 同样是一个完整、不可变的值：

```java
public Response(int status, Headers headers, byte[] body)

public int status()
public Headers headers()
public byte[] body()
```

状态必须在 100–599 之间。body 在构造和读取时都会复制；`null` 与空字节数组都表示零字节。

当前契约禁止非空 body 出现在 1xx、204 或 304 响应中。Handler 也不得设置 `Content-Length` 或 `Transfer-Encoding`，无论名称是什么大小写、值是否相同或是否为空。这是响应帧的所有权规则：Handler 描述表示内容和应用字段，适配器决定 wire 上的长度与传输方式。

一个普通文本响应可以这样写：

```java
return new Response(
        200,
        Headers.of("Content-Type", "text/plain; charset=utf-8"),
        "hello".getBytes(StandardCharsets.UTF_8));
```

一个删除成功的响应可以写：

```java
return new Response(204, Headers.empty(), null);
```

不要为了让 204 “看起来完整”而塞入提示文字。无内容状态的含义已经由状态码表达。

### HEAD 与 304 的边界

HEAD 的 Handler 仍返回与对应 GET 相同的表示内容。适配器在 wire 上抑制 body，并写出该表示的长度。当前实现只对原始 token 恰好为 `HEAD` 的请求抑制字节；`head` 会因 `HttpMethod` 的大小写归一化匹配 HEAD 路由，但在 JDK 上按普通响应发送 body。这个不对称行为是实测后固定的边界，见 [ADR-0028](../adr/0028-method-case-and-head-wire-boundary.md)。

304 不发送 body，也不发送 `Content-Length`。虽然 HTTP 标准允许 304 声明一个等于假设 200 响应长度的值，但当前框架无法在单个 304 调用中验证这个值，因此选择不声明，见 [ADR-0029](../adr/0029-response-framing-and-304-length.md)。

## 6. 错误响应由哪一层负责

`Handler` 不负责替路由器回答错误，也不应把异常细节写进响应。当前 JDK 适配器的职责如下：

| 状态 | 触发条件 | 响应特征 |
| --- | --- | --- |
| 400 | 请求目标无法构成合法 `Request`，或请求体读取失败 | `text/plain; charset=utf-8`，正文为 `Bad Request` |
| 404 | 路径没有任何方法覆盖 | 正文为 `Not Found`，不回显请求路径 |
| 405 | 路径存在，但方法不支持 | 正文为 `Method Not Allowed`，并带稳定排序的 `Allow` |
| 413 | 请求体超过配置上限 | 正文为 `Content Too Large` |
| 500 | Handler 抛出 `RuntimeException` | 正文为 `Internal Server Error`，不向客户端返回异常消息或堆栈 |

Handler 自己仍可以返回 400、401、403、404 等业务状态；上表描述的是适配器在路由和传输边界生成的错误。两者的区别在于：业务 Handler 知道错误原因，适配器只知道通用失败类别。

当前适配器会记录 Handler 异常的完整堆栈供服务端诊断，同时让客户端只看到固定文本。日志中的 method 和 path 也是应用数据，部署时仍需考虑敏感路径与日志访问权限。

错误契约的完整理由见 [ADR-0023](../adr/0023-http-error-semantics.md)；请求体上限见 [ADR-0031](../adr/0031-request-body-limit.md)。

## 7. 一个完整的读写示例

[RequestResponseExample.java](../../examples/src/main/java/com/barezen/examples/requestresponse/RequestResponseExample.java) 注册了三个真实 Handler：

```java
.route(HttpMethod.POST, "/echo", (request, parameters) ->
    new Response(200, Headers.of("Content-Type", "text/plain; charset=utf-8"), request.body()))
.route(HttpMethod.GET, "/describe", (request, parameters) -> text(describe(request)))
.route(HttpMethod.DELETE, "/gone", (request, parameters) ->
    new Response(204, Headers.empty(), null))
```

其中 `/echo` 直接返回请求体副本，`/describe` 读取 `X-Name` 和原始 query，`/gone` 返回无 body 的 204。示例的 `main()` 还直接构造 `Request` 并调用路由表，因此不启动 HTTP 服务也能观察契约行为。

在仓库根目录运行示例模块的测试：

```bash
mvn -o -B -ntp -pl examples -am test
```

这条命令也会构建示例依赖的框架模块。若要单独验证 HTTP 契约：

```bash
mvn -o -B -ntp -pl framework/barezen-web test
```

相关测试包括 `RouterTest`、`RequestTest`、`ResponseTest`、`HeadersTest` 与 `HttpMethodTest`。它们验证的是契约值和匹配规则；wire 上的 HEAD、304、长度与重复字段行为则由 `barezen-http-jdk` 的测试覆盖。

## 8. 设计边界小结

- `Router` 是可独立测试的纯匹配器，不启动服务器，也不负责网络错误。
- `Request` 和 `Response` 都是完整值，生命周期边界清楚，不暴露可变的内部数组。
- 路由参数与 query 参数是两种不同输入；前者由模式捕获，后者只是可选原始字符串。
- 路径保持原始文本，安全假设不能建立在“路由器已经解码或归一化”之上。
- `Headers` 保留重复值和大小写不敏感语义，不把它简化成单值 map。
- Handler 拥有业务响应，适配器拥有请求读取、错误分类和 wire framing。
- 这些边界来自 [ADR-0018](../adr/0018-http-is-a-contract-not-a-server.md)、[ADR-0019](../adr/0019-router-semantics.md)、[ADR-0020](../adr/0020-route-parameters-contract.md)、[ADR-0027](../adr/0027-http-response-body-semantics.md) 与 [ADR-0029](../adr/0029-response-framing-and-304-length.md) 的共同约束，而不是可以按页面需要随意绕过的实现细节。
