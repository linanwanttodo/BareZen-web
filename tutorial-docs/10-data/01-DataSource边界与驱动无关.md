# 第 10 章第 1 步：DataSource 边界与驱动无关

## 这一步要解决什么

这是第一次让 BareZen 接触到真实数据库，但实现的第一行代码不是 SQL，而是 `new Database(dataSource)`。问题在于：一个可被很多应用采用的 HTTP 框架，是否应该在发布物里携带 H2、读取 `jdbc.url`、猜测用户名和密码？如果会，那么测试数据库就不再只是测试工具，而变成了所有消费者继承的产品选择。第 1 步要把连接创建留在应用装配处，让 data 模块只说 JDK 自带的 `javax.sql`。

当前实现位于 `5c2cd05`。它新增九个生产类型：`Database`、`Transaction`、`TransactionCallback`、`RowMapper`、`DataAccessException`、`JdbcCollect`、`Migration`、`Migrator` 与 `AppliedMigration`。其中 `JdbcCollect` 是 package-private 的共享原语，不对外扩张 API。模块 POM 的 compile 依赖为空，H2、JUnit、AssertJ、ArchUnit 全是 test scope；根 POM 与 BOM 只登记模块坐标，不引入驱动。

这里要同时解决四个问题：谁创建连接；单次 SQL 的游标由谁关闭；SQLException 到调用者时是什么；业务代码自己抛出的异常是否也应该被改写成数据库错误。前两个决定资源安全，后两个决定异常是否诚实。只完成“能查询”会漏掉框架最重要的部分，因为一次失败就可能泄漏连接、statement 或 ResultSet。

本步先实现 `Database`、`RowMapper`、`JdbcCollect` 和 `DataAccessException`，把 `transaction` 作为下一步的入口保留。此时 `query` 与 `executeUpdate` 都是各自借一条连接的完整单元；它们不是事务容器。两次 `executeUpdate` 成功后再失败，第一次仍可能已经提交，所以“两个 auto-commit 调用”绝不能被称为一个原子工作单元。

## 先想清楚再写

第一种常见方案是 `Database.of(Configuration)`：框架读取数据源键，调用 `DriverManager.getConnection(url, user, password)`。它用起来短，却让框架同时拥有产品选择、配置解析和连接生命周期。应用换数据库时可能必须改框架；测试设一个系统属性就能改变生产行为；框架为了支持连接字符串格式又需要维护方言。ADR-0038 拒绝的不是配置本身，而是基础设施越过自己的签名偷偷读取配置。

第二种方案是引入完整 repository 抽象：实体、映射注册表、query builder、变更追踪。它可以隐藏 SQL，却无法让成本消失。缓存何时失效、懒加载何时发出 SQL、事务由哪个代理管理，都成为新的框架承诺。本章需要的是小而可审计的 SQL 边界，因此保留 SQL 文本，只消除连接获取、参数绑定、游标推进和异常翻译这些机械重复。

第三种方案是暴露 `Connection` 或 `Statement` 给 repository。它当然最灵活，也最糟：调用者可以不关闭资源，可以自行 commit，可以把驱动异常一路抛到 HTTP 层。正确边界应当比“所有 JDBC 功能”更窄。`RowMapper<T>` 接收已经定位的 `ResultSet`，只返回一个 `T`；框架继续 `next()`、收集列表并关闭游标。mapper 不拥有读取到哪一行、何时停止、资源何时释放的权力。

参数通过 `PreparedStatement.setObject(index, value)` 绑定，而不是拼进 SQL。这样问号是值占位符，客户端输入不会改变 SQL 结构。代价也必须说清：`setObject` 不推断 `null` 的 SQL 类型，驱动可能要求 `setNull(typeName)`；当前 API 不承诺跨驱动的 null 类型推断。它选择可移植的简单绑定，并把驱动拒绝作为 `DataAccessException` 返回，而不是增加一个参数类型注册系统。

异常翻译只覆盖数据层产生的 `SQLException`。获取连接、准备语句、执行、读取列和 mapper 主动抛出的 SQLException，都应带 SQL 或操作上下文到达调用者，并保留原 cause。事务 callback 里的 `IllegalStateException` 是应用失败，不应被包装成“数据库坏了”；这个原则在本步只作为契约埋下，下一步会用回滚测试固定。还要避免把参数值写进消息，因为参数往往就是用户数据。

## 动手

先写公开入口。`Database` 是 final 类，只保存一个 `DataSource` 字段。构造器立即拒绝 null，避免一个直到第一次查询才暴露的半成品对象。`query` 在借连接后用 try-with-resources 同时管理 PreparedStatement 与 ResultSet，`executeUpdate` 只管理 statement；两者的 finally 都释放连接。

```java
public Database(DataSource dataSource) {
  this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
}

public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
  Objects.requireNonNull(mapper, "mapper");
  Connection connection = acquire();
  try (PreparedStatement statement = JdbcCollect.prepared(connection, sql, parameters);
      ResultSet resultSet = statement.executeQuery()) {
    return JdbcCollect.rows(resultSet, mapper);
  } catch (SQLException e) {
    throw new DataAccessException("query failed: " + sql, e);
  } finally {
    release(connection);
  }
}
```

源码见 [Database.java:67](../../framework/barezen-data/src/main/java/com/barezen/data/Database.java#L67) 和 [Database.java:80](../../framework/barezen-data/src/main/java/com/barezen/data/Database.java#L80)。不要从示例复制整段实现：这段代码值得逐行读，是因为资源顺序已经 encoded 在 try-with-resources 与 finally 中。ResultSet 先关闭，statement 随后关闭，最后连接归还；即使 executeQuery 或 mapper 失败，这个结构仍然成立。

`JdbcCollect.prepared` 解决一个不那么明显的泄漏点：`connection.prepareStatement(sql)` 已经成功，但绑定参数时失败，此时 statement 尚未进入调用方 try-with-resources 的资源列表。若直接返回，异常会绕过自动关闭。因此它先创建 statement，在内部 bind；bind 抛出 `SQLException | RuntimeException | Error` 时主动关闭 statement，并把 close 失败作为 suppressed 附在原异常上，再重抛。

```java
static PreparedStatement prepared(Connection connection, String sql, Object[] parameters)
    throws SQLException {
  PreparedStatement statement = connection.prepareStatement(sql);
  try {
    bind(statement, parameters);
    return statement;
  } catch (SQLException | RuntimeException | Error e) {
    try {
      statement.close();
    } catch (SQLException ignored) {
      e.addSuppressed(ignored);
    }
    throw e;
  }
}
```

这段位于 [JdbcCollect.java:28](../../framework/barezen-data/src/main/java/com/barezen/data/JdbcCollect.java#L28)。`bind` 使用从 1 开始的位置，把 Java 数组下标 0 对应 JDBC 参数 1；`rows` 使用 `while (resultSet.next())`，逐行调用 mapper 并加入 `ArrayList`。它不捕获 mapper 的 SQLException，而是让外层翻译点补上 SQL 上下文。`null` mapper 在借连接前拒绝，因此无效调用不会消耗池资源。

`acquire` 只调用 `dataSource.getConnection()`，并把 SQLException 翻成 `DataAccessException("could not acquire a connection from the data source", e)`。`release` 尝试 close，但 close 的 SQLException 不覆盖已经产生的查询结果或业务异常。这里必须准确表述：框架保证尝试关闭，不保证一个拒绝关闭的坏 DataSource 真的没有泄漏；真实连接池是否隔离坏连接属于池的行为。Javadoc 的“每个连接都关闭”是正常 JDBC 实现下的目标契约，不能扩大成对所有外部实现的强制保证。

`DataAccessException` 直接继承 `RuntimeException`，不继承 `com.barezen.core.error` 的类型。配置或装配错误通常要改代码，数据错误通常要查数据库、SQL 或 schema，把两者塞进同一个 catch 会诱导错误处理。它有无 cause 与有 cause 两个构造器；当前所有框架产生的 JDBC 翻译都保留 cause。SQL 进入消息，参数值不进入消息。

POM 边界见 [barezen-data/pom.xml:21](../../framework/barezen-data/pom.xml#L21)。H2 只在测试中出现，`Database` 的公开签名也不暴露 H2 类型。应用可以在自己的装配代码中选择 H2、PostgreSQL 驱动或池实现，然后传入 `DataSource`；这不是“框架支持所有驱动”的承诺，而是“框架没有提前选一个驱动”的边界。

## 常见的第一次写错

1. **错误：在 data 模块写 `jdbc:h2:mem:test` 或导入 `org.h2.jdbcx.JdbcDataSource`。** 正确：驱动配置属于应用或测试 fixture；ArchUnit 会禁止主源码依赖 H2、PostgreSQL、MySQL 等包。
2. **错误：把 `String sql` 与参数用 `+` 拼接。** 正确：保留 `?`，通过 PreparedStatement 按位置绑定；结构不能来自输入，值也不能逃进异常消息。
3. **错误：让 `RowMapper` 返回整个 ResultSet 或自己调用 `next()`。** 正确：mapper 只读当前行并返回 `T`，框架拥有 cursor 和列表形状。
4. **错误：在 mapper 中 catch SQLException 后返回 null。** 正确：让异常到达统一翻译点；null 可能是合法业务值，不能兼任失败哨兵。
5. **错误：只给成功查询写 try-with-resources。** 正确：准备、绑定、执行、读取和 mapper 失败都必须关闭资源；绑定失败尤其容易在 statement 返回前泄漏。
6. **错误：捕获所有异常并统一包装。** 正确：翻译 SQLException；业务 RuntimeException/Error 的性质不能被伪造成数据库失败。
7. **错误：把两次 executeUpdate 当作原子写入。** 正确：每次调用是独立单元；需要原子性时进入下一步的显式事务。
8. **错误：忽略 null 参数跨驱动差异。** 正确：当前用 `setObject` 绑定 SQL NULL，遇到需要类型的驱动会失败并翻译，不应假装推断出了类型。

## 怎么确认这一步没造错

先运行边界 CRUD 与异常翻译测试：

```bash
mvn -o -B -ntp -pl framework/barezen-data -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=DatabaseTest,DatabaseLifecycleTest,BarezenDataArchitectureTest
```

预期 `DatabaseTest` 8 项、`DatabaseLifecycleTest` 11 项、`BarezenDataArchitectureTest` 7 项全部通过。`DatabaseTest.fullCycleThroughTheBoundary` 在真实 H2 上完成 insert、query、update、delete，并用 `WHERE id = ?` 证明参数绑定。`nullParametersAreAllowed` 不仅插入 null，还专门使用无参数查询，避免测试误把 varargs 的零参数与一个 null 参数混淆。

生命周期测试不是检查一个自己维护的 `closed=true`，而是用 `RecordingDataSource` 包装真实 H2。该记录器以 JDK `Proxy` 拦截 Connection、Statement、ResultSet，把未关闭资源保存在 `openConnections/openStatements/openCursors`。因此删除生产代码中的 close 会让列表非空；绑定失败会让 statement 留在 `openStatements()`。这比“模拟一个总会响应 close 的 fake”更接近真实 JDBC 协议。

重点观察三个失败点：坏 SQL 保留 SQLException cause 并关闭连接；mapper 的 SQLException 消息包含原 SQL；`failNextBind()` 注入的 setObject 失败不会留下 statement。获取连接失败与 `setAutoCommit(false)` 失败会在下一步事务测试中覆盖，本步只需知道 acquire 是唯一借连接入口。

架构测试的七条规则同样必须运行：data 不依赖 core/web/http/security/plugin/app，不命名数据库驱动，不调用 DriverManager 或 Class.forName，不使用 ThreadLocal/执行器，不读取系统属性或环境变量，主源码不依赖日志 facade，也没有静态可变字段。它们防止的是未来一次“临时加个全局连接缓存”或“打条 SQL 日志”的演进，而不仅是当前代码是否漂亮。

## 此刻的代码长什么样

现在应用已经能在不接触 Connection 的情况下完成单次 CRUD，并且每条公开入口都有资源与异常边界。示例应用尚未把这段能力装配成完整流程，但公共类型已经足够：应用实现 `RowMapper`，把 SQL 和值交给 `Database`，接收列表或受影响行数。driver、URL、凭据留在 `DataSource` 内部，框架只看见 `getConnection()`。

这仍然不足以表达真实业务。比如“创建笔记，再给账本增加一条记录”需要两次写同时成功；若第二次失败，第一次不能留下。`query` 与 `executeUpdate` 每次借新连接，天然无法承担这个承诺。下一步引入 `Transaction` 与 `TransactionCallback`，让应用在一次回调内得到同一个事务对象，同时把 commit/rollback 的可见性收回到 data 包内。

到这一步也应记住已固定与未解决的分界。固定的是无驱动 compile 依赖、参数化查询、框架拥有 cursor、SQLException 统一翻译与资源关闭路径；未解决的是事务传播、池的选择与调优、null 类型提示、流式读取和跨数据库产品行为。它们不是本步遗漏，而是需要新需求才能合理增加的 API。
