# 03 消费者迁移与 HEAD 显式注册

## 这一步要解决什么

StaticContent 已经能独立通过行为测试，但架构决定不能没有真实消费者。`326c25a` 引入它时，文档站还是一个由 BareZen 提供 HTTP 服务的 `site/` Maven 模块；ADR-0051 也明确把那个站命名为第一个消费者。随后部署目标确定为 GitHub Pages：Pages 从分支与文件夹提供静态文件，不运行 BareZen 的 main、adapter 或 ETag 逻辑。

`9aeb03e` 因此不是小范围清理，而是一次消费者迁移。它删除 `site/pom.xml`、`Site.java` 和 `SiteTest.java`，把资源树摊平为 `site/index.html`、`site/docs/*.html`、`site/zh/...` 与 `site/assets/site.css`，同时新增 `examples/staticcontent/StaticFileExample` 和对应 TestKit 测试。内容模块不再被框架内部服务器消费，而由一个公开 API 示例承担真实运行证据。

这一步还暴露了一个原文档站没有测到的事实：Router 按客户端发送的方法匹配，GET-only 路由不会自动接受 HEAD。adapter 的 HEAD 规则只决定“已经有 Handler 的响应如何写”，不会替应用补一条路由。新的示例为每个文件同时注册 GET 与 HEAD，并增加一个 GET-only 对照测试，把 405 固定下来。

最终读者得到两种发布方式的清晰边界：Pages 直接托管文件，不再有 BareZen 条件缓存；StaticFileExample 在本地真实端口演示 200、304、HEAD 与未注册路径 404。两者不是同一部署的两种预览模式，也不会被要求产生完全相同的 ETag 行为。

## 先想清楚再写

先问“代码到底运行在哪里”。若目标只是本机预览，可以保留 site 模块；若线上是 Pages，服务器 main 不会被执行。继续维护一个只能本地运行的服务，会让开发者验证 `/docs/plugins` 扩展less 地址、ETag 重载或 `/health`，上线后这些行为全部不存在。两个视图越相似，这种差异越容易隐藏。选择纯静态目录，就是让本地文件和线上 artifact 成为同一棵树。

但删除消费者不能把模块变成无人使用的框架装饰。于是把例子放到 `examples` 叶子：它能依赖 content、web、adapter 与 testkit，却没有反向进入框架生产的路径。例子通过编译器保证只调用公开 API；private FileHandler 与 package-private 方法不会被示例绕过。测试也不再复制一份 Handler，而是直接调用示例的 router，从而证明“文档里教人注册的那段代码”确实可运行。

资源枚举放在应用装配期。示例对应用选定的目录调用 Files.list，过滤普通文件、排序，再为每个文件名注册 `/name`。目录只列一层，不递归，不生成目录索引；请求不提供任何 Path 参数。这个限制很重要：当前不是通用静态主机，不能把“一文件一路由”夸大为“任意目录树可发布”。若未来需要子目录与动态文件集，ADR-0051 要求连同 wildcard 优先级和路径包含检查一起重新设计。

HEAD 必须显式注册，因为方法匹配在 Handler 之前。GET-only 表在 HEAD 请求下没有匹配项，adapter 只能给出自己的 405；它没有机会调用 StaticContent，更无法从 Response 推导长度。示例为每个文件注册两个 Handler 调用，虽然文件 Path 与类型相同，但路由项是两个独立方法键。只有表里存在 HEAD 入口，adapter 才进入“返回完整表示、线上抑制正文”的分支。

协议 token 的大小写是另一个边界。HttpMethod.of 会把 `head`、`Head` 折叠到 HEAD 常量，所以路由可以选择 HEAD Handler；JDK 的 ExchangeImpl 却只把原始 token 恰好等于 `HEAD` 的请求视为 HEAD。adapter 遵循服务器的精确比较，因此 `head` 会走 HEAD Handler，但在当前 JDK 上按普通响应发送正文和真实长度。注册方法解决“有没有 Handler”，原始 token 解决“线上是否抑制正文”，两者不能混成一句“HEAD 自动继承 GET”。

Pages 的地址约束也来自同一部署事实。项目页通常位于 `/<repo>/site/`，`href="/assets/site.css"` 会离开项目子树，所以全部链接必须相对；扩展less 路由曾经由服务器提供，Pages 不提供，因此保留 `.html`。语言继续留在地址里，两个 landing page 通过 reciprocal hreflang 配对。`404.html` 加 JavaScript 重写被拒绝，因为页面寻找样式表不应依赖脚本，文本浏览器和自动检查也不应看到另一套 URL。

## 动手

示例注册路由的核心在 [StaticFileExample.java:44](../../examples/src/main/java/com/barezen/examples/staticcontent/StaticFileExample.java#L44)：

```java
try (Stream<Path> files = Files.list(directory)) {
  List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
  for (Path file : sorted) {
    String address = "/" + file.getFileName();
    builder.route(HttpMethod.GET, address, StaticContent.file(file));
    builder.route(HttpMethod.HEAD, address, StaticContent.file(file));
  }
}
```

这里用 try-with-resources 关闭目录流，排序让路由表可重复，过滤避免把子目录当文件。它没有递归，也没有把完整磁盘路径放进 URL。两次 StaticContent.file 调用都返回读取同一文件的 Handler；可以先创建一个 Handler 再注册两个方法，行为等价，当前源码选择直白的两行。

main 在 `127.0.0.1:0` 启动真实 adapter，端口由操作系统分配。第一次 GET 读取 ETag，第二次携带相同 If-None-Match，第三次发送 HEAD，最后请求未注册路径。每一步打印实际观察，而不是只写“静态服务器已启动”。server.stop 放在 finally，客户端 InterruptedException 被保留为 cause 后转为 IllegalStateException。它不是长期运行服务，而是可重复执行的教学程序。

GET-only 反例写在 [StaticFileExampleTest.java:63](../../examples/src/test/java/com/barezen/examples/staticcontent/StaticFileExampleTest.java#L63)：

```java
Router getOnly = Router.builder()
    .route(HttpMethod.GET, "/notes.txt", StaticContent.file(file))
    .build();
try (TestServer server = TestServer.start(getOnly)) {
  Assertions.assertThat(server.client().get("/notes.txt")).statusIs(200);
  Assertions.assertThat(server.client().head("/notes.txt")).statusIs(405);
}
```

测试同时证明正常路径与缺失路径。只写 HEAD 成功会漏掉“应用忘记注册”的真实错误；把 405 当作失败去修 Router，则会破坏方法表的明确语义。正确修复位置是装配代码。

文档站的四项保证移到 [scripts/check-site.sh:34](../../scripts/check-site.sh#L34)：每个 href/src 在磁盘存在、每个页面从对应语言 landing page 可达、每页有互链的另一语言版本、没有绝对 `/` 地址；此外还要求唯一 `assets/site.css`。检查脚本是普通文件检查器，不启动 HTTP 服务，也不假设 Pages 支持 BareZen 的 framing。

## 常见的第一次写错

- **错误：** 保留 site 服务器作为“本地预览”，同时部署同一目录。**正确：** 选择一种真实交付方式，避免预览拥有线上没有的路由与缓存语义。
- **错误：** 删除 site 模块后忘记给 content 找消费者。**正确：** 用 examples 的可运行程序和套接字测试继续证明公开 API。
- **错误：** 以为 adapter 会把 HEAD 自动映射到 GET。**正确：** 每个文件显式注册 GET 与 HEAD，GET-only 的 405 是协议表的结果。
- **错误：** 把方法大小写折叠与 HEAD 线上抑制当成同一规则。**正确：** 路由按方法值，线上 framing 按原始 token，两层各有测试。
- **错误：** 在 Pages 页面写绝对 `/assets/...`。**正确：** 使用相对地址并保留 `.html`，由磁盘检查脚本验证。
- **错误：** 用 Files.walk 递归后只取文件名。**正确：** 当前示例只枚举一层；目录树需要新的地址与路由决定。
- **错误：** 声称 Pages 会返回 BareZen 的 ETag。**正确：** 条件请求证据来自 StaticFileExample，不在纯静态部署上承诺。

## 怎么确认这一步没造错

运行示例的 5 个真实套接字测试：

```bash
mvn -o -B -ntp -pl examples -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=StaticFileExampleTest
```

本次实跑 5 项，0 失败、0 错误。测试分别验证每个平铺文件以自己的名称注册、相同 ETag 的 reload 返回无正文无长度的 304、HEAD 返回表示长度但不发送正文、GET-only 返回 405、未注册地址返回 404 且错误方法返回 405。TestServer 使用真实端口，不是直接调用 Handler 的替代品。

直接执行示例可以观察最终输出。仓库根目录先构建测试产物，然后使用 examples README 记录的 classpath 方式；下面增加 `$PWD` 是为了避免相对 classpath 的歧义：

```bash
M2="$HOME/.m2/repository"
CP="$PWD/examples/target/classes"
for m in core web http-jdk content security data plugins; do
  CP="$CP:$PWD/framework/barezen-$m/target/classes"
done
CP="$CP:$M2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar:$M2/com/h2database/h2/2.2.224/h2-2.2.224.jar"
java -cp "$CP" com.barezen.examples.staticcontent.StaticFileExample
```

本次实跑看到两个文件生成四条路由：`GET /notes.txt`、`HEAD /notes.txt`、`GET /status.json`、`HEAD /status.json`；notes 为 44 字节，ETag 为 `"ec185bc08f4786af"`；reload 为 304、0 字节、Content-Length absent；HEAD 声明 44、收到 0 字节；未注册路径为 404、正文 `Not Found`。临时目录名每次不同，不是固定输出。

再执行 `./scripts/check-site.sh`。本次当前树实测输出 `checked 34 pages, 1 stylesheet(s)` 与 `OK: the static site is self-consistent`。ADR-0054 记录的是当时 12 页，文档引用历史结果时必须标明时间，不能把旧数字当成当前验收结果。

## 此刻的代码长什么样

barezen-content 现在有 shipped example 作为消费者，而文档站回归“文件夹就是交付物”。应用在装配时枚举明确目录，为每个文件注册两个方法；示例既打印观察，也用真实套接字固定这些观察。Pages 只检查文件与相对链接，不假装提供条件缓存或扩展less 路由。

仍要诚实保留限制：枚举不递归、没有 Cache-Control、每次请求全量读取、没有 Range 或流式下载；文档站不会得到 ETag。下一步把模块行为、架构边界、字节码基线、示例和站点脚本放在同一条检查路径中，形成可重复的章节验收。
