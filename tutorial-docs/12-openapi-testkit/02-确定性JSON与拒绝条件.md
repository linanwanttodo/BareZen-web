# 第 12 章第 2 步：确定性 JSON 与拒绝条件

## 这一步要解决什么

上一步已经能构造一份 checked document，但“结构正确”还不等于“字节稳定”。同一组操作先加入 `/users` 再加入 `/health`，与反过来加入，是否得到完全相同的 JSON？组件 schema 来自 `LinkedHashMap`，security scheme 也来自 map，emitter 若再排序一次，会不会把已经明确的顺序打乱？summary 含引号、换行或控制字符时，输出还是不是合法 JSON？

这些问题不能靠“JSON 语义相同”蒙混过去。OpenAPI 文档常被直接放进 git diff、缓存、内容哈希和测试快照；键顺序漂移会让没有语义变化的提交出现整页 diff，也让字节级回归测试失去意义。当前阶段把**相同声明产生字节等价文本**当作硬约束，因此必须同时决定模型顺序和 emitter 行为。

本步继续读提交 `e49587f7bb0a347ad0e215131a9c9aa1c40cbcfe` 中的 `ApiDocument`、`OpenApiJson`、`JsonWriter`，并对照 `fae6b463b85701c4263c23212194525550d91a7f` 补出的 golden、无体响应和 README 示例测试。[ADR-0043 第 3、6、7 条](../../docs/adr/0043-openapi-generator-is-a-declared-data-projection.md)把责任写得很清楚：schema 的循环安全和属性顺序是结构性的；六类拒绝在 build 时完成；document 拥有最终排序，emitter 只渲染，不添加任何顺序决策。

这里还要避免把“确定性”误解成“所有集合都按字典序”。servers 有偏好顺序，responses 与 parameters 保留应用声明顺序，schema properties 也保留声明顺序；只有 paths、path 内 methods、组件名称、security scheme 名称和去重后的 tags 由 document 排序。错误地把所有列表排序，会破坏“先放首选 server”“先写 404 再写 200”这类有意义的声明。

## 先想清楚再写

先画一张顺序责任表。collector 的 `operations()` 已按 path、method 排序，但 document 不能假设所有 operation 都来自 collector；应用也可能直接构造 `Operation`。因此 document 必须自己建立 path 分组，并按模板文本排序。同一 path 内的 operation 按 `HttpMethod.text()` 排序，例如 DELETE 在 GET 之前；输出键再转为小写。组件 schema 与 security scheme 按名称排序，tags 从全部 operation 汇总后去重排序。

Operation 则保留参数、响应和 security requirement 的声明顺序。响应存储用 `LinkedHashMap`，构造后包在 `Collections.unmodifiableMap` 中。这里不能随便换成 `Map.copyOf`：虽然它返回不可变 map，但当前实现不能依赖它保留 `LinkedHashMap` 的迭代顺序。代码注释明确写出了这个约束，测试则用 404 在 200 之前的完整片段固定输出。

第二项设计是 emitter 不引入第三方 JSON 库。任何通用库都可能拥有自己的 map 策略、数字格式、转义规则和空白输出；即使今天配置成固定顺序，升级后也需要重新证明。当前 emitter 只有字符串、布尔、对象和数组值，模型已经完成全部语义，因此一小段 package-private writer 足以把顺序变成代码中的调用顺序。`JsonWriter` 不公开，应用依赖的是 `ApiDocument.toJson()`，而不是一个需要永久维护的通用 JSON API。

手写 emitter 的最小状态是容器栈。每个 frame 记住自己是 array 还是 object，以及是否已经写过第一个成员。object 的逗号由 `field(name)` 写，array 的逗号由 `valueStart()` 写；否则对象值前可能多一个逗号，或者数组元素粘在一起。关闭容器时还要检查类型与栈顶，`finish()` 拒绝未闭合结构。这个实现不是为了支持任意文档，而是为了让当前渲染调用序列一旦写错就立即失败，而不是悄悄产出半个 JSON。

转义也要固定。双引号、反斜线、退格、换行、回车、制表符使用常见短转义；其余小于 `0x20` 的字符写成四位小写 `\u00xx`。Locale 固定为 ROOT，避免不同运行环境改变十六进制文本。当前 writer 不提供 number/null 方法，因为模型中的布尔是显式 primitive，可选字段通过“不输出”表达，`$ref` 与 status code 都是字符串。限制 API 反而缩小了需要证明的格式面。

第三项设计是六类拒绝。它们不是随意挑选的检查，而是“一份文档可能教客户端做错什么”的分类：

- 缺必需字段：document 的 title/version，response 的 description，operation 的至少一个 response。
- 重复 `(method, path)`：同一路线身份出现两次。
- 重复 operationId：不同模板经过派生后撞成同一个客户端可用标识。
- 无效响应：状态文本非法、1xx/204/205/304 带 body schema，或 operation 没有响应。
- 悬空 `$ref`：operation payload 或组件内部引用了不存在的组件。
- 无效 security requirement：operation 使用了 document 未声明的 scheme。

wrong path parameter 也在构造期拒绝，但发生在 Operation 层，不等待 document。同一 `(location, name)` 重复声明同样在 Operation 层拒绝；不同 location 的同名参数仍是两个合法参数。模板语法本身也与 Router 对齐：部分花括号、非法标识符、重复 path 名称和 query 都在提取参数时失败。把校验放在不同层不是分类混乱，而是由可见信息决定：operation 看得见自己的模板和参数，document 才看得见所有组件和 scheme。

第四项设计是 operationId 派生而不是声明。`GET /users/{id}` 去掉方法大小写与路径分隔符并首字母大写，得到 `getUsersId`。这减少了应用需要同步维护的字段，却也制造了可检测的碰撞：`/users/{id}` 与 `/users/id` 都得到 `getUsersId`。document 用第二遍扫描拒绝碰撞，并指出两个原始 operation，而不是等客户端生成器覆盖其中一个。

最后是循环引用校验。`requireResolved` 遇到 REFERENCE 只检查目标名是否存在然后返回；遇到 object 递归检查每个 property，遇到 array 递归检查 items。组件表本身也逐个检查，因此“某个组件引用了缺失 User，但所有 operation 都没直接使用它”同样会失败。自引用不会循环，因为引用节点不再展开目标。这里的终止保证与渲染终止保证来自同一个数据结构，而不是两个碰巧正确的 visited set。

## 动手

document 的顺序在 [ApiDocument.java:47](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/ApiDocument.java#L47) 一次性建立。schemas 与 security schemes 通过 `sortedByName` 变成不可变 `LinkedHashMap`；tags 从 operation metadata 流式收集、distinct、sorted；paths 先按模板分组，再排序模板和 method。最后才执行 `validate(builder)`。构造器没有把半成品暴露给调用者，所以“先构造、后验证”仍然满足 build 失败即没有可用 document。

跨 operation 的两遍校验值得逐字读：

```java
Set<String> seen = new java.util.HashSet<>();
for (Operation operation : builder.operations) {
  String id = operation.method().text() + " " + operation.path();
  if (!seen.add(id)) {
    throw new IllegalArgumentException("the document registers operation " + id
        + " twice; one method and one path is one operation (a duplicated path is a spec that lies)");
  }
}
Map<String, String> ids = new LinkedHashMap<>();
for (Operation operation : builder.operations) {
  String id = operation.method().text() + " " + operation.path();
  String previous = ids.putIfAbsent(operationId(operation), id);
  if (previous != null) {
    throw new IllegalArgumentException("operations " + previous + " and " + id
        + " project to the same operationId \"" + operationId(operation)
        + "\"; the collision is in the path templates (e.g. /users/{id} vs /users/id, which differ only in"
        + " the braces the derivation strips), and a client generator keys on this string");
  }
}
```

先检查重复路线，再检查派生 id，错误诊断才准确。两个完全相同的 GET `/users` 同时也会撞 operationId，但“同一 operation 声明两次”比“两个模板碰撞”更接近根因。测试分别覆盖这两件事，避免实现只在第二个循环抛一个泛化消息。

引用检查位于 [ApiDocument.java:101](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/ApiDocument.java#L101)。它遍历 operation 的 parameters、requestBody、responses，再遍历每个 security requirement，最后检查组件 schema 自身。任何 `$ref` 不存在都会抛出包含使用位置与目标名的 `IllegalArgumentException`。security 则只检查 scheme name 是否声明；当前 bearer scheme 不是 OAuth2，因此 `Security.Requirement.of` 直接拒绝非空 scopes，不会输出 OpenAPI 3.0.3 禁止的 scope 数组。

渲染入口 [OpenApiJson.java:28](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/OpenApiJson.java#L28) 先写 `openapi`，再写 `info`，然后按 document 顺序写 servers、paths、components、tags。空 servers/components/tags 不输出，避免制造无意义字段。operation 的字段顺序固定为 operationId、summary、tags、parameters、requestBody、security、responses；response 先写 description，有 body schema 才写 content。

schema 的 REFERENCE 分支只有五条有效语句：

```java
if (schema.type() == Schema.SchemaType.REFERENCE) {
  w.objectStart();
  w.field("$ref").string("#/components/schemas/" + schema.refName());
  w.objectEnd();
  return;
}
```

它位于 [OpenApiJson.java:175](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/OpenApiJson.java#L175)。正因为这里没有递归查找目标，`User.friends = array(ref User)` 只展开一次 User 组件，friends 节点保持引用字符串。`nullable` 只在显式 true 时输出；required 来自 property flags，不另存一份列表。

`JsonWriter` 的关键不是转义，而是分隔符所有权。[JsonWriter.java:52](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/JsonWriter.java#L52) 的 `field` 只在 object frame 中写逗号、键名和冒号；[JsonWriter.java:100](../../framework/barezen-plugins/src/main/java/com/barezen/plugins/openapi/JsonWriter.java#L100) 的 `valueStart` 只在 array frame 中写逗号。把两者合并会很难判断一个值究竟位于对象还是数组，当前拆分让误用立即抛出 `IllegalStateException`。

最小 document 的 golden bytes 是很好的整体验收：

```json
{"openapi":"3.0.3","info":{"title":"Library","version":"2.0"},"paths":{"/health":{"get":{"operationId":"getHealth","summary":"Liveness","responses":{"200":{"description":"alive"}}}}}}
```

测试比较的是完整字符串，不是 contains，也不是解析后比较对象。少一个逗号、多一个字段、换一个键顺序都会失败。完整 document 的 golden 进一步覆盖 servers、components、security、参数、请求体、数组 schema 与自引用，避免最小样例通过但真实功能顺序漂移。

## 常见的第一次写错

1. **错误：只排序 paths，不排序 path 内 methods。** 正确：document 同时固定两层顺序。
2. **错误：把所有列表按字典序排序。** 正确：servers、responses、parameters、properties 保留声明顺序。
3. **错误：用 `HashMap` 存 responses，再声称输出确定。** 正确：使用 `LinkedHashMap` 并测试声明顺序。
4. **错误：让 emitter 自行决定未知字段顺序。** 正确：顺序在 document/operation 模型建立，emitter 只按固定调用序列输出。
5. **错误：用 `Map.copyOf` 替换响应 map 的不可变包装。** 正确：当前实现需要保留迭代顺序，不能只凭“不可变”判断等价。
6. **错误：遇到 `$ref` 就递归生成目标。** 正确：引用只输出名字，目标作为组件根输出一次。
7. **错误：只检查 operation 中出现的引用。** 正确：未被 operation 直接使用的组件也可能含悬空引用。
8. **错误：先检查 operationId 碰撞，再检查重复路线。** 正确：先报告更直接的重复 `(method, path)`。
9. **错误：把 golden 写成解析后的结构比较。** 正确：字节确定性必须比较完整字符串，并单独验证转义。
10. **错误：认为 build 通过等于完整 OpenAPI 合法。** 正确：当前只验证声明子集，不是外部 3.0.3 校验器。
11. **错误：只按参数名去重。** 正确：身份是 `(location, name)`；query `q` 与 header `q` 可以共存，同位置同名必须拒绝。

## 怎么确认这一步没造错

运行：

```bash
mvn -o -B -ntp -pl framework/barezen-plugins -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=OpenApiModelTest,OpenApiDocumentTest,JsonWriterTest,ReadmeDocumentExampleTest
```

这组共 58 项：OpenApiModel 30、OpenApiDocument 20、JsonWriter 7、README example 1；本章统一检查点中全部通过。`OpenApiDocumentTest.Determinism` 改变 operation 装配顺序，`responseOrderIsDeclaredOrderInOutput` 固定 404 在 200 前；`orderIsDefinedNotAccidental` 检查 `/alpha` 在 `/mike` 前、DELETE 在 GET 前。它们分别防止“无序但偶尔相同”和“排序过度”两种回归。

`JsonWriterTest` 的 Output 4 项覆盖对象/数组顺序、嵌套空容器、转义和非 ASCII 原样保留；Misuse 3 项覆盖在数组里写 field、关闭不匹配容器和未闭合 document。只测试正常输出不足以证明 writer 安全：如果 object/array 栈检查永远通过，渲染调用写错时仍可能悄悄产出错误文本，因此拒绝路径也必须可执行。

还要做两个手工反例。第一，把 `/users/{id}` 与 `/users/id` 同时放入 document，应得到 `getUsersId` 碰撞，而不是两条 operation 都被接受。第二，构造 `Schema.object(List.of(new Schema.Property("friends", Schema.array(Schema.ref("User")), false)))`，把它注册为 `User`，生成必须返回，并且 JSON 中 User 的 required 列表只出现一次。前者检验跨模板 identity，后者检验结构性终止，二者都不能只靠代码阅读宣布成立。

README 示例测试把 collector → Operation → ApiDocument → Router → handler 的路径完整执行一次，最后比较响应体与 `document.toJson()`，并检查 bearer security 与 `$ref` 确实出现。它证明示例不是装饰性伪代码，也证明投影没有反向增加 collector 状态。

## 此刻的代码长什么样

到这里，文档生成器同时具备两种能力：不诚实的声明在 build 时失败，相同声明按固定规则产生相同字节。`ApiDocument` 是公开边界，`OpenApiJson` 与 `JsonWriter` 是包内实现；应用不需要理解 writer 的容器栈，但必须理解自己的声明顺序哪些有意义、哪些会被 document 重排。

`handler()` 也已出现：它在装配时调用一次 `toJson()`，把 UTF-8 字节保存在 lambda 中，每次请求返回同一个 Response。因而 document 服务不需要 per-request 生成，也不会被某个请求改变状态。下一步暂时离开 plugins，去建立 TestKit；因为生成器自己的测试只能证明“模型和直接 handler 调用正确”，不能证明这些字节经过真实适配器后仍然正确。
