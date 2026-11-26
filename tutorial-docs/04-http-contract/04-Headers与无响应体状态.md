# 04 Headers 与无响应体状态

## 这一步要解决什么

Request 和 Response 的形状确定后，还剩下两个最容易在“示例能跑”时漏掉的问题：HTTP Header 不是普通 Map，某些状态也根本不接受消息体。重复字段被覆盖、CR/LF 被拼进 Location、204 带 body、Handler 自己写 Content-Length，都会让客户端看到的响应与代码生成的不同。

本章把 Header 建模为有序字段行，并在 Response 构造时拒绝不合法组合。目标是尽早、明确地失败，而不是等字节写到 socket 后由服务器丢弃、去重或发出相互矛盾的分帧信息。

## 先想清楚再写

HTTP 允许同名 Header 出现多次。Map<String,String> 只能保留一个值；把值逗号拼接也不可靠，因为 Set-Cookie 的值本身就可能含逗号和分号。Headers 因此保存每个字段行，values(name) 返回全部值，first(name) 只服务本来只有一个值的字段。

匹配大小写不敏感，拼写保持原样。Headers.of("Content-Type", ...) 写入后仍显示 Content-Type，不会在构造时全部小写。names() 返回每个不同名称第一次出现时的拼写；若 Set-Cookie 和 set-cookie 同时出现，values 保留两行，names 只列一次。这样信息不丢，读取也有稳定规则。

first 与 values 也不是同一需求的两种别名。first 刻意保留“第一个值”的 wire 顺序，适用于本来就只有单值的字段；values 才用于需要处理全部字段行的场景。框架没有替应用决定多个 Location、多个 Content-Type 或合并 Link 值时怎样解释，因为那是具体协议消费方的责任。把领域解析塞进 Headers，会让所有请求和响应都承担一套无法覆盖全部字段的规则，也会诱使调用方忽略 first 只返回首项这一事实。容器只保证可靠保存和查找，语义由使用这些字段的小类型决定。

缺失与空值不同。没有 X-Cursor 时 first 是 Optional.empty()，发送 `X-Cursor:` 时是 Optional.of("")。这会影响“没有游标”和“游标位于起点”的业务解释。读取 null 名称直接拒绝，空字符串名称也拒绝，因为那不是一次有效的字段查询。

Headers 没有 builder、set 或 entries Map。with 返回新对象，同名再次调用意味着追加字段，不是替换。要替换，就明确构造想要的新集合。少量字段下复制列表的成本可以接受，换来的是跨 Handler、装饰器和适配器传递时不会被背后修改。

名称必须是 HTTP token，值不能包含控制字符。后一条是响应拆分防线：如果 Location 或 Content-Disposition 由用户输入拼接，CR/LF 会开启新字段甚至新响应行。当前实现连 tab 也拒绝，理由是实现明确选择更严格的字段值边界，避免折叠续行歧义；这不是在解析每个 Header 的领域语法。

错误消息本身也安全。被拒绝的 CR/LF 不会原样进入日志，而是输出四位 `\u000D`、`\u000A`。[neverRepeatsTheRejectedCharacterInItsOwnMessage](../../framework/barezen-web/src/test/java/com/barezen/web/HeadersTest.java#L201) 同时检查转义文本存在和原始换行不存在。否则防御逻辑把攻击字符挡在 Header 外，却通过异常消息把它送进日志。

toString 只输出字段名和数量，不输出值。Authorization、Cookie 或自定义 token 可能出现在 Header 中；Request/Response 的 toString 同样不打印 body 和 Header 值。需要诊断值时，应用必须显式选择要记录哪个字段，而不是被默认 toString 泄露。

Response 构造还要拒绝两种非法组合。第一，1xx、204、304 不允许非空 body。HTTP 服务器可能静默丢弃这些字节，JDK 对 204 还会给出强制长度警告；客户端最终没有收到 Handler 生成的 body。构造时拒绝能把错误指回开发者写下的那一行。

第二，Content-Length 和 Transfer-Encoding 不允许出现在 Response Headers。它们描述字节怎样分帧，只有真正写字节的适配器才知道。Handler 设置它们可能造成 204 客户端失败、304/HEAD 双 Content-Length，或 chunked 与 identity body 相互矛盾。拒绝按字段名判断，大小写、空值和重复值都不例外。

HEAD 与 304 常被混为一谈，但规则不同。HEAD 的 Response 可以保留 GET representation 的 body，适配器不发送这些字节，却发送由 body 长度推导的 Content-Length。304 按当前 ADR-0029 不发送 body，也不发送 Content-Length；它本来就没有本次要发送的 representation 可供计数。

因此 Response 只负责“这个状态能不能携带 body”和“Handler 能不能声明 framing”，不负责完整 wire 矩阵。200 空 body 的 Content-Length 是 0，200 非空是 body 长度，204/304 两种 framing 都缺席，HEAD 是 representation 长度且无 body，404/405/500 是普通 body 长度。Transfer-Encoding 在当前 JDK 适配器中始终不写。

## 动手

- [Headers.java:90](../../framework/barezen-web/src/main/java/com/barezen/web/Headers.java#L90)：of 创建单字段，with 追加并返回新实例。
- [Headers.java:128](../../framework/barezen-web/src/main/java/com/barezen/web/Headers.java#L128)：first 返回 Optional，大小写不敏感匹配。
- [Headers.java:149](../../framework/barezen-web/src/main/java/com/barezen/web/Headers.java#L149)：values 保留同名字段的所有值和顺序。
- [Headers.java:221](../../framework/barezen-web/src/main/java/com/barezen/web/Headers.java#L221)：名称 token 与值控制字符校验。
- [HeadersTest.java:23](../../framework/barezen-web/src/test/java/com/barezen/web/HeadersTest.java#L23)：大小写、重复、缺失、安全拒绝、不可变和脱敏测试。
- [Response.java:179](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L179)：1xx/204/304 非空 body 拒绝。
- [Response.java:205](../../framework/barezen-web/src/main/java/com/barezen/web/Response.java#L205)：两种 framing 字段拒绝。
- [ResponseTest.java:197](../../framework/barezen-web/src/test/java/com/barezen/web/ResponseTest.java#L197)：无体状态与 framing 的全部构造测试。
- [ADR-0029 决策表](../../docs/adr/0029-response-framing-and-304-length.md#L106)：200/204/304/HEAD/404/405/500 的最终 wire 矩阵。

`32ebc41` 已建立 Headers 的有序行模型；`ef1f5db` 把响应体与 framing 规则加入 Response；`ace40f5` 后来用一张真实 socket 表把多个状态组合固定下来。本章先解释构造规则，第六章再用适配器证明它们与 wire 输出一致。

## 常见的第一次写错

用 Map 存 Header 会静默覆盖 Set-Cookie；用逗号 join 会把一个字段值的语法破坏成框架规定的组合规则。允许空名称或控制字符，会让无效字段进入输出；把 CR/LF 原样放进异常消息，则响应拆分防线在日志层被绕过。

只对 204 禁 body，会漏掉 1xx 与 304；只拒绝 Content-Length 的非空值，会允许空值或重复 framing 进入 socket；只按精确大写匹配，则 content-length 可以绕过。正确规则是按字段名大小写不敏感地拒绝整个字段，而不是校验一个看似合理的数字。

## 怎么确认这一步没造错

Headers 测试按规则分组，而不是按方法逐个测试。大小写组覆盖三种拼写和前缀不匹配；重复组证明 Link 与 Set-Cookie 不拼接；缺失组证明 Optional 的空值语义；安全组覆盖非法名称、CR、LF、tab、BEL、DEL 和脱敏错误消息；不可变组证明 with 不影响原对象、返回列表不可修改。

ResponseTest 的 BodylessStatuses 遍历 100、101、103、199、204、304，确认非空 body 全部拒绝、空 body 全部允许，同时确认 200、201、206、301、404、500 等普通状态仍可带 body。FramingFields 覆盖大小写、空值、重复相同值、重复不同值和两种字段同时存在。

```bash
mvn -o -B -ntp -pl framework/barezen-web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=HeadersTest,ResponseTest
```

当前 HeadersTest 25 项、ResponseTest 26 项。完整矩阵的真实 socket 测试属于 JDK 适配器，本章不能仅凭这两个单元测试类声称已经验证 HEAD 与 304 的 wire 输出。

## 此刻的代码长什么样

Headers 现在完整保留字段行且不可变，Response 在构造时拒绝无体状态带 body 和 Handler 自带 framing。你已拥有可独立测试的 HTTP 契约。下一步运行模块检查点，证明这些能力不依赖真实服务器，并明确哪些 wire 行为仍需后续适配器验证。
