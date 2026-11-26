# 第 10 章：数据边界不是 ORM，而是连接、事务与版本的归属

## 本章目标

前九章解决了请求如何进入框架、身份如何被识别、对象如何在装配期获得配置。现在要把“变化必须持久化”加入系统，但第一步不是选择数据库，也不是画实体关系，而是决定框架允许谁知道什么。`barezen-data` 的答案很窄：应用交给 `Database` 一个已经完成的 `javax.sql.DataSource`，框架提供单次查询、单次更新和显式事务；应用提供迁移列表，框架按版本执行并把结果写进自己的历史表。

这条边界让框架保持轻，但不意味着可以随便转发 JDBC。`Database` 若拿到 URL 自己 `Class.forName`，测试里的 H2 就会被带进生产假设；若让每个 repository 自己开关事务，多个写操作就可能分别提交；若把连接放进 `ThreadLocal`，同一线程的下一个请求会继承一个隐式工作单元。本章的每个 API 都是在关闭这些容易发生的错误，而不是提供一层“更方便的 SQL 别名”。

当前主线建造过程由四个提交串起来。`5c2cd05` 加入数据模块、九个生产类型、根 POM 与 BOM 坐标，此时事务与迁移契约已经存在；`25337a1` 加入真实 H2、JDK 动态代理记录器、六类测试与架构门禁；`b143aa4` 写下 ADR-0038—0040，把驱动无关、事务归属和迁移历史写成可复审的决定；`0971abc` 加入七个示例，其中数据库示例把公共 API 串成可运行路径。测试先证明行为，ADR 再解释取舍，示例最后证明陌生人只靠公开类型也能开始。

最终 API 可以压缩成三句话：`query` 与 `executeUpdate` 每次借一条连接，调用结束就关闭；`transaction` 把同一条 `Transaction` 显式传给回调，由框架且只由框架决定提交；`Migrator.migrate` 跳过已记录版本、拒绝倒序补号、逐版本提交，并以历史表而不是“schema 看起来怎样”作为权威。它不是 Spring Boot 替代品，没有自动配置数据源、ORM、实体变更追踪、连接池、仓库基类或注解事务。

完成本章后，你应能从一行 `new Database(dataSource)` 解释驱动、URL、凭据和连接池为何属于应用；能从异常路径说出 commit、rollback、释放连接的顺序；能证明回调外无法调用 commit；能设计可重跑的 migration，并明确 DDL 失败后历史记录与真实 schema 可能不一致。你还要记住：嵌套 `transaction` 会开启两个独立事务，而不是加入外层；`setObject` 不推断 `null` 的 SQL 类型；迁移没有 down、checksum、资源扫描和并发锁。

## 涉及 ADR

- [ADR-0038：持久化只经过 javax.sql 边界，框架不命名驱动](../../docs/adr/0038-jdbc-boundary-and-driver-free-data-layer.md)：选择应用提供 `DataSource`，而不是框架读取配置建连接。数据模块零 compile 依赖，SQLException 在边界统一翻译，业务回调的 RuntimeException 保持原样；参数值不进入异常消息。
- [ADR-0039：事务对象由调用者持有，决策者始终不可见](../../docs/adr/0039-transaction-ownership-and-package-private-deciders.md)：`Transaction` 作为参数进入工作单元，commit/rollback 是 package-private。决策表覆盖正常返回、SQLException、RuntimeException/Error、commit 失败与 rollback 失败；嵌套明确开启第二条独立事务。
- [ADR-0040：迁移历史是权威，DDL 不保证回滚](../../docs/adr/0040-migration-history-authority-and-measured-ddl-rollback.md)：一个版本只有在 apply、历史插入和 commit 正常返回后才计入本次成功列表；commit 响应失败时以实读历史为准。H2 实测显示回滚后的 CREATE TABLE 仍存在，因此恢复规则是重跑未记录版本并修复，而不是承诺 schema 还原。

还应回看第 7 章的 ADR-0013：连接事实在装配处解析并作为对象交给基础设施。数据模块不读取 `System.getProperty`、`System.getenv` 或 `Configuration`，也不是 service locator；这正是第 7 章显式装配原则在持久化边界上的复用。

## 本章提交地图

| 提交 | 当前主线上的真实变化 | 阅读重点 |
| --- | --- | --- |
| `5c2cd0572cd524880e75fb52b5deb9e0391ae901` | 新增 `barezen-data` 模块与九个生产类型，12 文件、1172 行新增 | 第一次提交就包含 DataSource、事务、迁移，不是先 CRUD 后补边界 |
| `25337a1f3c62ae09ad4d402142031e244857ad90` | 新增 H2 fixture、RecordingDataSource、行为/生命周期/迁移/架构/字节码测试，9 文件、2023 行新增 | 用真实驱动和 JDK 代理证明调用顺序，而不是用自写 fake 证明想象 |
| `b143aa4bba4c40eb453bf81d066d9f5fef3d8c7c` | 新增 ADR-0038—0040，更新 README 与 ADR 索引 | 记录被否决的 ORM、环境配置、ambient transaction 与 DDL 回滚承诺 |
| `0971abc64b56f72430f572ffa9536cd0815997c5` | 新增 examples 叶子模块与七个可运行示例，16 文件、1089 行新增 | 应用选 H2，框架只接收 DataSource；DatabaseExample 把事务、查询和回滚串成公共 API 路径 |

```bash
git show --stat 5c2cd05
git show 25337a1 -- framework/barezen-data/src/test/java/com/barezen/data/RecordingDataSource.java
git show b143aa4 -- docs/adr/0040-migration-history-authority-and-measured-ddl-rollback.md
git show 0971abc -- examples/src/main/java/com/barezen/examples/database/DatabaseExample.java
```

本教程统一使用 `git log HEAD` 能追溯的主线短哈希；`git log --all` 会把不同分支的相同阶段一起显示，不能据此误判执行顺序。可用 `git merge-base --is-ancestor 5c2cd05 HEAD`、`git merge-base --is-ancestor 25337a1 HEAD` 与 `git merge-base --is-ancestor 0971abc HEAD` 验证本章采用的主线坐标。

## 步骤导航

1. [DataSource 边界与驱动无关](01-DataSource边界与驱动无关.md)：先决定“谁创建连接”，再实现参数绑定、游标归属、异常翻译和资源关闭。
2. [显式事务与归属规则](02-显式事务与归属规则.md)：把 commit/rollback 藏在 package-private 后面，用决策表与 JDBC 调用序列验证每条失败路径。
3. [版本化迁移与回滚权威](03-版本化迁移与回滚权威.md)：用历史表标识已完成版本，处理乱序、重复、失败重跑和 H2 的隐式 DDL 提交。
4. [检查点：真实驱动跑通](04-检查点-真实驱动跑通.md)：组合运行 67 项模块测试、2 项示例测试和真实 main，并列出尚未证明的产品级能力。

## 本章完成后的能力清单

你应能把 SQL 与值分开：SQL 决定结构，参数通过 `PreparedStatement.setObject` 按位置绑定；`RowMapper` 只读当前行，不能保存或推进 `ResultSet`。你应能在查询、更新、绑定失败、mapper 抛 SQLException、获取连接失败和关闭资源失败时，判断调用者拿到什么异常、原 SQLException 是否仍在 cause 中、哪些资源已经尝试关闭。

你应能把多条写操作放进一个 `database.transaction(tx -> ...)`，在回调内全部使用传入的 `tx`，并把返回值带回外层。你应能解释为什么 callback 内的 `SQLException` 会变成 `DataAccessException`，而 `IllegalStateException` 会原样重抛；为什么 commit 失败后先 rollback；为什么 rollback 失败不能盖住最初业务异常；为什么事务结束后保存的 `Transaction` 不能继续使用。

你应能构造正整数版本、唯一版本号与非空 description，把 migration 列表以任意顺序交给 `Migrator`，并从 `history()` 读取数据库事实。你应能拒绝版本 7 在版本 12 之后补跑，允许版本 1 后直接到版本 4 的间隔，识别完整旧列表再次启动不是“倒序”，并让失败的 DDL 通过 `IF NOT EXISTS` 在下次启动重跑成功。

## 不这样设计会坏掉什么

若框架读取 `jdbc.url` 自己加载驱动，生产包就多出一个测试引擎，换数据库也可能变成改框架。若把实体、query DSL、变更追踪都塞进这一层，每种能力都带来依赖、缓存和生命周期，而本章需要的只是 CRUD、事务和迁移。若用字符串拼接参数，SQL 注入会直接进入数据库；若把参数值写进异常或日志，敏感行数据又会进入日志系统。

若每个 repository 管理自己的连接并提交，服务层的两次写就不再是一个工作单元。若用 `ThreadLocal` 保存事务，repository 的签名看不见事务来源，线程复用还会污染下一次请求。若把 commit/rollback 设为 public，“repository 不应提交”就只是建议；若允许 Transaction 越过回调生存，连接会被长期占用，连接池最终耗尽。若把嵌套事务偷偷加入外层，外层失败时内层已提交的数据不会消失，用户却会得到“全部回滚”的错误印象。

若每次启动运行所有 migration，第二次 CREATE 就会失败；若只保存一个“最后版本”，无法区分 1、2、4 与 1、2、3、4。若对最低待执行版本与最高已应用版本的比较放在过滤之前，每次正常启动都会把旧版本 1 误判成倒序。若承诺“失败 migration 不留任何痕迹”，H2 已经实测否定它：DDL 可能留下孤儿表。若 migration 不可重跑，下一次启动会持续撞上自己留下的对象；若修改已应用的 migration SQL 而不换版本，当前没有 checksum 能发现这种历史篡改。

最后，67 项测试不是“支持所有数据库”的证明。它只证明当前公共 API 在 H2 与注入失败点上满足契约；JDK 17 字节码契约也只证明 class major 61，不证明在 JDK 17 上完成过多产品矩阵。覆盖率、测试数和真实示例都应如实陈述，不能替代 PostgreSQL、MySQL、真实连接池或并发部署的独立验收。

## 下一章预告

第 11 章进入 `barezen-plugins`。数据模块把应用依赖作为构造参数接收，插件模块则要决定框架如何接收一个陌生人的扩展：服务发现、类加载、生命周期先后、失败隔离与调用边界在哪里。两章共同坚持“显式装配”，但插件解决的是运行期扩展，而不是数据库连接；第 10 章的 `DataSource` 仍然应由应用或插件在装配阶段提供，不能变成 data 模块内部的全局服务。
