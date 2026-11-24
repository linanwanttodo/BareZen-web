# 第 5 章：最小路由器

## 本章目标

第四章冻结了请求与响应的形状，却留下一个实际问题：应用写出了 Handler，谁来决定某个请求该交给哪一个？如果在每个 Handler 前手动比较 method 和 path，路由规则会散落在业务代码里；如果直接让 Router 返回 404，错误格式、异常政策和传输层又会重新耦合。本章要建造的是这两者之间的一块小表：输入 Request，输出“选中的 Handler 与捕获参数”，不调用 Handler，也不生成 Response。

完成本章后，你应能用一句话说清全部语义：按原始 `/` 分段，段数必须相同；字面量段精确且大小写敏感比较，`{name}` 段捕获一个非空的原始段；同一请求命中多个形状时，从左到右第一个“字面量对参数”的位置决定字面量获胜；同方法同形状重复注册直接失败。其余如 404/405 状态、Allow 字段、异常翻译与 socket 写出，都留给适配器。

这不是一个支持通配符、正则、注解扫描或热更新的通用路由引擎。它用一次线性扫描回答每个请求，刻意用 O(routes) 的简单实现换一组可以完整陈述和测试的规则。只有当真实消费者证明需要 trie 或新的匹配形状时，才增加语义与 ADR，而不是提前搭一个庞大的路径框架。

## 涉及 ADR

- [ADR-0019：Router Semantics](../../docs/adr/0019-router-semantics.md#L100)：初始路由表、原始段匹配、形状碰撞、字面量优先、不可变表和 404/405 的数据边界。
- [ADR-0020：Route Parameters Contract](../../docs/adr/0020-route-parameters-contract.md#L51)：把路由捕获 Map 直接传给 Handler，删除旧单参数重载，并增加架构规则防止参数偷偷回到 Request。
- [ADR-0018：HTTP 是契约](../../docs/adr/0018-http-is-a-contract-not-a-server.md#L55)：契约不分发 Handler、不生成路由错误响应的底座；其中旧 Handler 签名是历史快照。
- [ADR-0026：OPTIONS *](../../docs/adr/0026-options-star-semantics.md)：真实 JDK 请求目标实测；Router 对 `*` 返回空，但回复政策仍属于适配器。
- [ADR-0047：扩展点声明](../../docs/adr/0047-an-extension-point-is-declared-by-the-component-that-owns-it.md)：后续给 Builder 增加 `contributing`；本章只说明它与初始 `route` 的关系，完整插件汇合留在第九章。

阅读 ADR-0019 时要注意两处历史签名：它写成 `handle(Request)`，后续示例也直接调用单参数版本。当前代码已经由 ADR-0020 改成 `handle(Request, Map<String, String>)`。教程凡涉及可运行调用，都以当前源码为准。

## 步骤导航

1. [路由表只回答一个问题](01-路由表只回答一个问题.md)：明确 match、methodsFor、RouteMatch 与 adapter 的职责分界。
2. [原始段匹配：不解码不归一化](02-原始段匹配-不解码不归一化.md)：从 segmentsOf 推导精确段数、编码、大小写、尾斜杠和星号规则。
3. [字面量优先的优先级规则](03-字面量优先的优先级规则.md)：用同一个 shape 概念完成碰撞检测和确定性优先级。
4. [参数段提取与契约](04-参数段提取与契约.md)：按模式位置捕获原始值，并把 Map 原样交给 Handler。
5. [不可变路由表与并发匹配](05-不可变路由表与并发匹配.md)：理解 build 复制、每次匹配新建 Map 和无锁并发的真正原因。
6. [检查点：路由矩阵断言](06-检查点-路由矩阵断言.md)：运行路由、契约、架构与示例测试，区分已证明与未证明的边界。

## 本章提交地图

| 提交 | 真实变化 | 阅读重点 |
| --- | --- | --- |
| `18e8fef` | 10 个文件、2919 行新增：Router、RouteMatch、ADR-0019、953 行 RouterTest、插件集成与架构规则 | 路由语义从第一版就由行为测试和越界规则共同固定 |
| `2eddb0d` | 10 个文件、1047 行新增：Handler 增加 Map 参数、删除旧重载、增加 Delivery 测试和两条规则 | 路由输出第一次真正到达 Handler，而不是停在 adapter |
| `74ea0fb` | 382 行 OPTIONS * 适配器测试，Router 增加 5 行星号分支 | 真实 socket 行为推动契约补充，但 Router 仍不拥有错误回复 |
| `d7c7e83` | 声明 RouteContributor，Builder 增加 contributing 与列表校验 | 后续扩展只复用 route 权限，不引入路由注册表或优先级 |

可以依次运行：

```bash
git show --stat 18e8fef
git show 2eddb0d -- framework/barezen-web/src/main/java/com/barezen/web/Handler.java
git show 74ea0fb -- framework/barezen-web/src/main/java/com/barezen/web/Router.java
```

`18e8fef` 的 RouterTest 已接近千行，说明“最小”指的是语义和公开表面，而不是拒绝充分测试。ADR-0019 §22 还记录了三个被测试发现的实现错误：参数名与路径下标错位、根路径和双斜杠形状碰撞、match 中无效的 best 比较。教程会在相应步骤解释这些错误为什么不是靠重构顺便消除的。

## 本章完成后的能力清单

你应能独立实现一个行为等价的 Router，并说明为什么不用 URI.normalize、为什么参数不能跨段、为什么 `/users` 与 `/users/` 是两个路由、为什么 `{id}` 与 `{userId}` 会碰撞。你应能用两个方法调用解释 404 与 405 的信息来源，同时承认 Router 本身不返回这两种状态。

你还应能追踪一次完整分派：适配器调用 match，读取 RouteMatch.handler 和 parameters，再把同一个 Request 与 Map 传给 Handler；Router 从不调用 Handler。对于并发请求，每次匹配产生自己的参数 Map，冻结后的路由表只读，因此不需要锁。最后，你能识别当前边界：没有通配符、没有动态注册、没有真实传输延迟或吞吐量证明。

## 不这样设计会坏掉什么

先注册先获胜会让两个插件的冲突取决于装配顺序；后注册覆盖会让前一个 Handler 无声消失。按参数字符串判断重复会让改名绕过碰撞检测。先解码再分段会把 `/a%2Fb` 从一段变成两段，Router 可能选中客户端没有请求的资源。把参数塞进 Request 会让包装器伪造路由结论，也让未路由测试构造出“有效但不完整”的请求。

另一种常见失败是让 Router 顺手返回 404。这样路由层开始拥有错误 Content-Type、JSON envelope 和异常格式，第二个适配器无法采用自己的政策。把 match 做成三态结果看似方便，却增加一个公共类型，也把“谁处理”和“哪些方法存在”两个独立问题绑死。Optional 加 Set 更小，也更难悄悄长大。

## 下一章预告

第六章将加入第一个真实服务器适配器。Router 只提供匹配结论，适配器负责把 HttpExchange 翻译成 Request、设置请求体上限、调用 Handler、捕获异常并写状态行与字节。下一章会重点验证 HEAD、304、方法大小写和分帧，因为这些结论无法由本章的内存路由测试证明。
