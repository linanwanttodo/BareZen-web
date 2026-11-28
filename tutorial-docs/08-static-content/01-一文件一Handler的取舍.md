# 01 一文件一 Handler 的取舍

## 这一步要解决什么

HTTP 契约、路由器和 adapter 已经能够处理普通响应，但“从磁盘读取一个文件”不能放进 `barezen-web`。契约模块刻意不依赖 `java.io` 或 `java.nio.file`，否则一个只想构造 Request 的消费者也会被迫理解文件系统；JDK adapter 也不能拥有业务资源目录，否则换 Servlet 或换部署方式都会改变资源策略。本步新建 `barezen-content`，让它只依赖 web 契约，并把一个现存文件包装成 Handler。

它的输入不是 URL，而是应用在装配阶段已经选定的 `Path`；输出也不是文件系统对象，而是普通 `Response`。因此认证装饰器、路由表、生命周期与 adapter 都不需要新增概念。应用注册这个 Handler 的位置决定谁能访问，请求路径只负责在既有路由表中选 Handler，不会成为磁盘查找的根目录参数。

本步采用“一文件一 Handler”，不是“一个目录一个 Handler”。Router 只接受字面路径段或单个 `{name}` 参数段，没有通配符、前缀匹配或多段捕获。静态服务器常见的 `/assets/*` 若引入，一个请求就可能同时命中静态规则和业务规则，路由器必须增加优先级。框架选择枚举应用已知的文件，把匹配保持为唯一答案。

真实变化位于 `326c25a`：新增模块 POM、StaticContent、StaticContentTest、RawHttp、架构测试和 ClassMajorContractTest，并把模块加入根 reactor 与 BOM。提交同时包含 TLS，说明一次提交可以覆盖几个主题；阅读时仍应按文件切片理解，不应把 TLS 也算成静态内容 API。

## 先想清楚再写

先画依赖，再决定文件放哪里。`barezen-content → barezen-web` 是生产边；`barezen-content → barezen-http-jdk` 只允许出现在 test scope，因为“Handler 返回什么”可以脱离服务器测试，“线上到底有没有 Content-Length”却必须让 adapter 真写一次。core、security、data、plugins 都不需要进入。若文件 Handler 自己读 Configuration，它会在请求期重新做装配决定；若自己检查身份，它就成为绕过路由装饰器的第二套安全层。

路径所有权比路径校验更重要。应用可以写 `StaticContent.file(root.resolve("app.css"))`，但 Handler 接口不接受请求目标作为文件参数。这样，未注册的 `/etc/passwd`、`/../secret` 或编码后的路径根本没有机会参与磁盘拼接。当前安全结论不是“已经写了强力的 traversal 过滤器”，而是“请求到文件之间没有参数通道”。未来若引入动态目录路由，这个结论会失效，必须重新回答包含检查与符号链接问题。

构造时机也要分开。应用启动时文件不存在，通常意味着打包漏文件或部署路径写错，应该立即失败并指向 path；如果注册后文件被删除，则是运行时资源状态，应返回客户端契约中的 404。前者抛 IllegalArgumentException，后者返回正文严格为 `Not Found` 的 Response，不带磁盘路径、原因或堆栈。把两者都推迟到请求期，会让部署错误变成每个客户端都碰到一次的错误。

类型来自固定表，而不是主机 MIME 数据库。`Files.probeContentType` 会询问操作系统或已安装探测器，同一个 `.js` 文件在两台机器可能得到不同结果；固定表让输出可预测。扩展名不认识的类型统一为 `application/octet-stream`，不是“猜它像文本”。需要 `application/manifest+json` 时，应用使用第二个重载明确声明，不让推断覆盖业务语义。

最后接受内存成本。Response 的 body 是 byte[]，所以 Files.readAllBytes 与现有契约一致；它适合样式、图标和小页面，不适合视频。流式读取会牵涉返回后谁写、客户端断开怎么办、HEAD 如何先知道长度，不能在本步用一个 OutputStream 参数绕开。

## 动手

本步最初公开三个磁盘文件方法，核心构造如下：

```java
public static Handler file(Path file) {
  return file(file, contentTypeOf(file.getFileName().toString()));
}

public static Handler file(Path file, String contentType) {
  Objects.requireNonNull(file, "the file to serve");
  Objects.requireNonNull(contentType, "the content type");
  if (contentType.isBlank()) {
    throw new IllegalArgumentException("the content type must not be blank");
  }
  if (!Files.isRegularFile(file)) {
    throw new IllegalArgumentException("no regular file at " + file);
  }
  return new FileHandler(file.toAbsolutePath().normalize(), contentType);
}
```

这里省略了实际源码的长错误消息，但校验顺序与 [StaticContent.java:73](../../framework/barezen-content/src/main/java/com/barezen/content/StaticContent.java#L73)、[StaticContent.java:89](../../framework/barezen-content/src/main/java/com/barezen/content/StaticContent.java#L89) 一致。保存绝对规范化路径，避免进程工作目录后来变化；FileHandler 的字段是 final，实例不在请求间保存可变状态。contentTypeOf 的完整固定表在 [StaticContent.java:153](../../framework/barezen-content/src/main/java/com/barezen/content/StaticContent.java#L153)。

应用侧注册就是两行，不需要新生命周期接口：

```java
Handler css = StaticContent.file(Path.of("site/assets/site.css"));
Router router = Router.builder()
    .route(HttpMethod.GET, "/assets/site.css", css)
    .route(HttpMethod.HEAD, "/assets/site.css", css)
    .build();
```

HEAD 为什么必须写两次路由，下一步会用 `9aeb03e` 的真实失败说明。Handler 本身不读取 method，只按调用者注册的入口执行；是否抑制正文由 adapter 决定。

当前公共 API 还包含 `resource(ClassLoader, name)` 与带显式类型的重载。资源名必须是相对路径，拒绝空段、`.`、`..`、反斜杠和绝对名；类加载器也由调用方显式传入，不隐式使用线程上下文加载器。与磁盘文件按请求读取不同，classpath 资源在装配时读取并冻结字节，因为 jar 内资源不会在运行期被业务代码改写。缺失资源在装配时失败，读取异常保留为 `IllegalStateException`，不会在请求期变成延迟 404。

请求期核心只是“读当前字节，失败返回固定 404”：

```java
byte[] content;
try {
  content = Files.readAllBytes(file);
} catch (IOException unreadable) {
  return NOT_FOUND;
}
return new Response(200,
    Headers.of("Content-Type", contentType).with("ETag", etagOf(content)), content);
```

这是 [StaticContent.java:220](../../framework/barezen-content/src/main/java/com/barezen/content/StaticContent.java#L220) 的教学摘录，条件请求分支留到下一步。文件不在构造时读入，因此编辑内容无需重启；每次请求重新读取也意味着本实现没有缓存，更不会在多个请求之间复用过期 byte[]。

## 常见的第一次写错

- **错误：** 在 Router 增加 `/assets/*`，再让一个 Handler 接收路径参数。**正确：** 应用枚举文件，每个文件注册字面路由，请求路径不进入文件系统。
- **错误：** 把 StaticContent 放进 web 或 adapter。**正确：** web 保持纯契约，能读磁盘的 capability 单独依赖 web。
- **错误：** 构造时缓存磁盘文件字节，或把 classpath 资源也说成每次请求重读。**正确：** `file(...)` 每次请求读取当前内容；`resource(...)` 在装配时冻结不可变资源字节。
- **错误：** 文件不存在也创建 Handler，等客户端得到 404。**正确：** 装配时拒绝；运行后消失才返回 404。
- **错误：** 404 写“找不到 /srv/app/site.css”。**正确：** 只返回 `Not Found`，不在客户端回显部署布局。
- **错误：** 使用主机 MIME 探测。**正确：** 固定表推断，未知类型为 binary，特殊类型由应用显式传入。
- **错误：** 声称已经支持大文件流式下载。**正确：** 当前全量读入 byte[]，大文件是明确限制。

## 怎么确认这一步没造错

先运行不涉及条件请求的三组行为测试：

```bash
mvn -o -B -ntp -pl framework/barezen-content -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=StaticContentTest$Serving,StaticContentTest$Types,StaticContentTest$Refusals,StaticContentTest$ClasspathResources'
```

本次实跑 16 项，0 失败、0 错误。Serving 5 项验证字节、推断类型、显式类型、修改后标签变化、相同内容相同标签与文件消失后的 404；Types 2 项逐项检查 21 个扩展映射及未知名称；Refusals 4 项检查目录、不存在路径、空类型和 null；ClasspathResources 5 项验证资源字节、真实 jar、ETag/304、显式类型、缺失资源和非法名称。标签算法本身下一步展开，这里已经能看到“修改文件后再次调用同一 Handler 会得到不同标签”，从而杀死构造期缓存。

再运行模块门禁：

```bash
mvn -o -B -ntp -pl framework/barezen-content -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=BarezenContentArchitectureTest,ClassMajorContractTest
```

本次实跑 10 项，0 失败。6 条 ArchUnit 规则检查只依赖契约、无服务器与网络类型、无静态可变字段、无隐藏环境配置、无 ThreadLocal、唯一公开类型；4 条字节码测试读取本模块实际 class 文件，输出 `checked=12 unique-majors=[61] expected=61`。POM 依赖与架构规则是两层证据：前者让越界 import 无法编译，后者让审阅者直接看见边界。

[StaticContentTest.java:103](../../framework/barezen-content/src/test/java/com/barezen/content/StaticContentTest.java#L103) 先创建 Handler 再删除文件，证明构造成功不代表永久可用；[BarezenContentArchitectureTest.java:35](../../framework/barezen-content/src/test/java/com/barezen/content/BarezenContentArchitectureTest.java#L35) 禁止 content 依赖 core 与 security。若把 FileHandler 改为 public，唯一公开类型规则失败；若加入 `Files.probeContentType`，固定表测试会在不同主机暴露差异，而模块职责断言也会提醒重新审阅。

## 此刻的代码长什么样

现在应用可以明确列出自己要公开的每个文件，文件读取器既不知道服务器，也不知道安全政策。不存在的资源在启动时暴露，运行中消失的资源只得到不泄露路径的 404；编辑文件会在下一次请求生效，类型不随操作系统变化。模块的三组基础行为测试已经通过，尚未单独展开条件请求与完整线上分帧的验收。

下一步给 200 增加可验证的身份：从本次读取的字节生成 ETag，接收客户端已经持有的验证器，匹配时返回 304。这里会第一次遇到“文件大小明明知道，为什么仍不写 Content-Length”的架构问题。
