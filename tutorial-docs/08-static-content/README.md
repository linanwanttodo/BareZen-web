# 第 8 章：静态内容

## 本章目标

前七章已经能装配对象、匹配路由并把 Response 写到真实套接字，但仍不能正确回答一个浏览器最常见的问题：这个样式表或页面现在是什么内容，我上次保存的副本还能用吗？本章增加 `barezen-content`，但它不是把目录整个暴露给网络，而是把“一个磁盘文件”变成“一个普通 Handler”。路由、认证、生命周期与响应分帧仍由前几章的组件负责。

最终形状很窄：应用在装配时选择文件，调用 `StaticContent.file(path)` 得到 Handler，再为 GET 和 HEAD 注册路由。每次请求读取当前字节、计算 ETag；`If-None-Match` 匹配就返回只有 ETag 的 304，否则返回 200、类型、ETag 与完整字节。需要把资源打进 jar 时，使用 `StaticContent.resource(classLoader, name)`；它在装配时校验并冻结资源字节。Content-Length 不由 Handler 声明，adapter 从实际要写的字节派生；304 没有长度，HEAD 有表示长度但不发送正文。

时间线上有两个不能混淆的消费者。`326c25a` 引入静态内容模块，最初服务于当时的文档站；`9aeb03e` 又把文档站改为 GitHub Pages 直接托管的纯文件目录，并把消费者移到 `examples/staticcontent/StaticFileExample`。所以当前源码中，site 不是 Maven 模块，StaticContent 也不会替 Pages 生成 ETag。教程讲实现，也讲这个决定为什么改变了部署方式。

完成本章后，你应能解释为什么不增加 `/assets/*`，为什么路径不能来自请求，为什么 ETag 取内容哈希而不是修改时间；你应能从真实套接字区分 200、304 与 HEAD 的正文和长度；还应能指出全量缓冲、平铺目录枚举和缺少 Cache-Control 的代价，而不是把这些边界说成已经支持的功能。

## 涉及 ADR

- [ADR-0051：一文件一 Handler，以及条件请求消费者对 304 决定的复测](../../docs/adr/0051-static-content-is-one-handler-per-file.md)：本章的主决定。它拒绝 wildcard、fall-through 与目录 Handler，选择应用枚举文件；同时固定 ETag、404、MIME、内存模型与非目标。
- [ADR-0054：文档是一文件夹静态文件](../../docs/adr/0054-documentation-is-a-folder-of-static-files.md)：Pages 不运行 BareZen，因此删除 site 服务器，把 StaticContent 的消费者转到示例程序，并发现 GET-only 路由不会自动接受 HEAD。
- [ADR-0029：响应分帧契约与 304 表示长度](../../docs/adr/0029-response-framing-and-304-length.md)：Handler 不写 Content-Length 或 Transfer-Encoding。304 没有正文、没有长度；HEAD 则声明 GET 表示的字节数但不发送它。
- [ADR-0028：方法大小写与 HEAD 的线上边界](../../docs/adr/0028-method-case-and-head-wire-boundary.md)：路由按折叠后的方法选择 Handler，adapter 只对原始 token 恰好为 HEAD 的请求执行线上抑制。它与“是否注册 HEAD 路由”是两个问题。
- [ADR-0030：响应体抽象](../../docs/adr/0030-response-body-abstraction.md)：Response 继续保存 byte[]。流式文件需要真实消费者、返回后写入者所有权和仍成立的分帧矩阵，不能仅为“先进”而增加。

## 步骤导航

1. [一文件一 Handler 的取舍](01-一文件一Handler的取舍.md)：建立独立 capability 模块，验证路径、类型与失败时机，理解为什么不把请求路径拼到目录。
2. [条件请求与 304 的长度规则](02-条件请求与304的长度规则.md)：从字节生成强 ETag，处理弱比较、列表与通配符，并在套接字上守住 304 无长度。
3. [消费者迁移与 HEAD 显式注册](03-消费者迁移与HEAD显式注册.md)：沿 `9aeb03e` 删除文档站服务器，把示例变成消费者，同时记录 Pages 的相对 URL、`.html` 与目录枚举边界。
4. [检查点：静态资源可用](04-检查点-静态资源可用.md)：组合运行 Handler、架构、字节码和示例测试，再直接运行 main 与站点检查脚本，核对实际输出。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `de55b51` | 记录 ADR-0028 与 ADR-0029 | 在静态消费者出现前，304 无长度与 HEAD 精确 token 规则已经写下 |
| `326c25a` | 新增 content 模块、StaticContent、18 个行为测试、6 条架构规则和字节码测试 | 同一提交也含 TLS，但本章只追静态文件切片 |
| `9aeb03e` | 删除 site/pom、Site 与 SiteTest，新增 StaticFileExample 及 5 个测试 | 部署目标改变了，消费者也必须改变；HEAD 405 在真实使用中暴露 |

用提交切片阅读，避免只从最终文件猜测功能为何存在：

```bash
git show --stat de55b51
git show --stat 326c25a
git show 326c25a -- framework/barezen-content/src/main/java/com/barezen/content/StaticContent.java
git show 326c25a -- framework/barezen-content/src/test/java/com/barezen/content/StaticContentTest.java
git show --stat 9aeb03e
git show 9aeb03e -- examples/src/main/java/com/barezen/examples/staticcontent/StaticFileExample.java
```

`326c25a` 的提交说明还记录了一个诚实的建造过程：第一次 verify 有两条架构规则失败，修复方式是按真实依赖扩充 JDK allow-list，并用不可继承的基类替换匿名 HTTPS 配置器，而不是关闭门禁。这个细节与静态内容无直接行为关系，却说明本章所有“边界”最终都要由构建证明。

## 本章完成后的能力清单

你应能从 `barezen-content/pom.xml` 看出，生产编译依赖只有 `barezen-web`，JDK adapter 只以 test scope 进入套接字验证。StaticContent 是唯一公开顶层类型，FileHandler 与 ResourceHandler 都是私有嵌套类；应用不能通过子类偷偷改变读取策略，只能选择重载参数或编写自己的 Handler。

你应能列出构造时拒绝与请求时回答：不存在、目录、空类型在装配时失败；装配后文件被删除或不可读则返回纯文本 404。前者是部署错误，后者是资源当前不可用。两者都不把文件系统路径回给客户端。

你应能说明 ETag 是带引号的 16 位十六进制字符串，来自 SHA-256 前 8 字节；相同内容跨文件和重启得到相同标签，内容改变则标签改变。If-None-Match 支持 `*`、逗号列表和 `W/` 弱标签，当前没有 If-Match、If-Modified-Since、Range、压缩或缓存策略。

你应能从路由表看见每个平铺文件对应 GET 与 HEAD 两条记录，并知道 Files.list 只枚举一层普通文件。动态资产集、子目录发布、大文件与 CDN 缓存都有明确 reopen trigger，当前实现没有偷偷承诺它们。

## 不这样设计会坏掉什么

若为了静态文件给 Router 增加 `/assets/*`，一个字面路由和通配路由可能同时命中，路由器必须回答优先级；这个第二匹配层正是原设计拒绝的。若做“业务 Handler 找不到再落到静态目录”的链，404 会取决于装饰器顺序，安全中间件也更容易被绕过。若把请求中的 `../secret` 直接 join 到资源根目录，应用就从“注册已知文件”退回到需要规范化与包含检查的路径服务器。

若用 Files.probeContentType，两个操作系统可能对同一扩展给出不同类型，浏览器行为随部署机器变化；若未知扩展猜成 text/html，上传内容又可能被解释执行。若 ETag 使用修改时间，同一内容复制到另一台机器会得到不同标签，而时间戳粒度还可能掩盖快速修改。若 Handler 在构造时缓存字节，修改后的资源永远不会更新；若每次请求前检查 mtime，又把时钟与缓存一致性拖进本可简单的读取流程。

若看到 304 知道文件大小就写 Content-Length，会重新打开 Handler 自行声明分帧的边界；这个值必须等于假设 200 的字节数，而框架无法在单个 Response 内验证它。若认为 HEAD 自动继承 GET，真实路由器会返回 405；若 Handler 为 HEAD 返回空数组，adapter 会得到错误零长度。最后，若文档站本地由 BareZen 提供 ETag、线上 Pages 不提供，本地预览就会验证一套部署根本不具备的行为。

## 下一章预告

第 9 章进入安全模块：密码如何只以不可逆摘要保存，会话如何被撤销，认证与授权为什么是三种不同结果。静态文件只负责应用明确注册的内容；是否允许读取它，将由普通 Handler 装饰器在路由层决定，而不是由文件读取器自己猜测。
