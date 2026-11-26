# 数据访问：DataSource、显式事务与版本化迁移

## 这份文档讲什么

`barezen-data` 只提供一层很薄的 JDBC 边界：应用交进来一个 `DataSource`，框架提供单语句读写、显式事务和版本化迁移。它不提供 ORM、实体、仓库基类、查询构造器或连接池，也不替应用选择数据库。

这份文档对应三个决策：

- [ADR-0038](../adr/0038-jdbc-boundary-and-driver-free-data-layer.md)：持久化只经过 `javax.sql` 边界，框架不命名驱动，不创建连接工厂。
- [ADR-0039](../adr/0039-transaction-ownership-and-package-private-deciders.md)：事务作为回调参数显式传递，只有 `Database` 能决定提交或回滚。
- [ADR-0040](../adr/0040-migration-history-authority-and-measured-ddl-rollback.md)：迁移历史是权威，但历史回滚不等于 schema 回滚。

## 1. 应用提供 DataSource，框架不选择数据库

唯一入口构造函数是：

```java
public Database(DataSource dataSource)
```

`Database` 只持有这个对象，并通过 `DataSource.getConnection()` 借连接。它不读取 JDBC URL、用户名、密码、环境变量或系统属性，也不调用 `DriverManager`，更不会通过反射加载驱动。连接池也是应用的选择：池可以藏在同一个 `DataSource` 后面，框架不需要知道。

```java
DataSource dataSource = DatabaseExample.dataSource();
Database database = new Database(dataSource);
```

[DatabaseExample.java](../../examples/src/main/java/com/barezen/examples/database/DatabaseExample.java) 在应用侧创建 H2 数据源，因为示例必须真的运行 SQL。这个依赖位置是边界的一部分：

| 位置 | H2 依赖 | 原因 |
| --- | --- | --- |
| `barezen-data/src/main` | 无 | 生产代码不依赖任何数据库产品 |
| `barezen-data` test scope | 有 | 用真实 JDBC 驱动验证事务、资源和迁移行为 |
| `examples` | compile scope | 示例应用自己选择 H2 |

`barezen-data` 的主产物没有任何编译期第三方依赖，也不依赖 `barezen-core`。`BarezenDataArchitectureTest` 用 ArchUnit 禁止生产代码导入数据库产品、连接池实现、其他框架层，并禁止环境读取、`ThreadLocal`、执行器和日志框架。

“驱动无关”不等于“SQL 方言无关”。类型、函数、锁行为、隔离级别和 DDL 的事务性仍由数据库产品决定；框架只是不替应用做这个选择。

## 2. 单语句使用 Database，多语句单元工作使用 transaction

公开 API 只有三组：

```java
public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters)
public int executeUpdate(String sql, Object... parameters)
public <T> T transaction(TransactionCallback<T> work)
```

`query` 与 `executeUpdate` 每次借一条连接，执行一条语句后关闭资源。写入沿用数据源的 auto-commit 语义，适合本身完整的一条写入：

```java
int affected = database.executeUpdate(
        "UPDATE notes SET body = ? WHERE id = ?", "updated", 1);

List<String> bodies = database.query(
        "SELECT body FROM notes WHERE id = ?", rs -> rs.getString("body"), 1);
```

不要把两个必须共同成功的写入写成两次 `database.executeUpdate(...)`：它们是两个独立提交，第二次失败时第一次不会撤回。

多语句单元工作必须进入 `transaction`，并始终通过回调参数 `tx` 使用同一条连接：

```java
int inserted = database.transaction(tx -> {
    tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 1, "first");
    tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 2, "second");
    return tx.query("SELECT COUNT(*) FROM notes", rs -> rs.getInt(1)).get(0);
});
```

`Transaction` 的公开操作与 `Database` 同形：

```java
public int executeUpdate(String sql, Object... parameters)
public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters)
```

服务方法拥有单元工作边界；它把 `tx` 显式传给每个仓储调用。仓储不应再调用 `Database.transaction`，也不应偷偷改用 `Database` 的单语句方法，否则那一步会离开当前事务。

## 3. 参数按位置绑定，行映射不拥有游标

`query` 和 `executeUpdate` 都使用 `PreparedStatement`，参数按 1-based JDBC 位置通过 `setObject` 绑定：

```java
database.executeUpdate(
        "INSERT INTO contact (id, name, score) VALUES (?, ?, ?)",
        1, "ada", 10);

database.executeUpdate(
        "INSERT INTO contact (id, name) VALUES (?, ?)", 2, null);
```

`null` 作为 SQL `NULL` 绑定，框架不提供类型提示。如果某个驱动需要显式 SQL 类型，当前 API 没有单独的 typed-null 重载；应把它视为驱动兼容性边界，不要通过拼接字符串绕过参数绑定。

需要注意 Java varargs：想把 `null` 作为唯一参数时，应使用 `(Object) null`；只写 `null` 可能被理解为整个参数数组为空。SQL 字符串和标识符不能靠 `?` 参数化；动态表名、排序方向等应由应用自己的受限白名单决定。

`RowMapper` 是函数式接口：

```java
public interface RowMapper<T> {
    T map(ResultSet resultSet) throws SQLException;
}
```

框架按结果集顺序逐行调用 mapper，并收集成 `List<T>`。mapper 只读当前行，不调用 `next()`，不关闭、推进或保存 `ResultSet`。它是同步回调，不是流式 API；大结果集会完整收集到内存中。

## 4. commit 与 rollback 对应用不可见

`Transaction.commit()`、`rollback()` 和 `close()` 都是包私有。应用无法提交、回滚或关闭一个已经收到的 `Transaction`，因此“仓储必须自己决定事务命运”在 API 上就不成立。

回调结果决定路径：

| 回调或提交结果 | 框架动作 | 调用方收到 |
| --- | --- | --- |
| 正常返回 | `commit()` | 回调的返回值 |
| 抛 `SQLException` | `rollback()`，随后翻译 | `DataAccessException`，原异常为 cause |
| 抛 `RuntimeException` | `rollback()` | 原异常，不包装 |
| 抛 `Error` | `rollback()` | 原 `Error` |
| 回调正常，但 `commit()` 失败 | 尽力 `rollback()`，再释放连接 | 提交失败的 `DataAccessException`；若 rollback 也失败，其 `SQLException` 作为 suppressed |
| 回调失败，且 `rollback()` 也失败 | 仍释放连接 | 原失败；回滚的 `DataAccessException` 作为 suppressed |

```java
try {
    database.transaction(tx -> {
        tx.executeUpdate("INSERT INTO notes (id, body) VALUES (?, ?)", 3, "doomed");
        throw new IllegalStateException("business failure");
    });
} catch (IllegalStateException expected) {
    // 这一行不会提交；IllegalStateException 原样到达调用方。
}
```

由 `tx.executeUpdate` 或 mapper 抛出的 `SQLException` 已经在数据方法内翻译为 `DataAccessException`。它作为 `RuntimeException` 进入外层事务时，会原样重抛，同时触发整个单元工作回滚；不会在每次仓储调用边界重复包装。

## 5. 事务只在回调内有效，嵌套不会加入外层事务

事务构造时关闭 auto-commit，决策后尝试恢复 auto-commit，再调用 `DataSource` 连接的 `close()`。对池来说，关闭通常表示归还连接；对非池数据源则表示物理关闭。

`Transaction` 只在回调期间有效。结束后再调用其公开 `query` 或 `executeUpdate`，会得到：

```text
transaction already committed or rolled back
```

不要把 `tx` 保存到字段、异步任务或回调外部，也不要跨线程继续使用。框架没有检查线程归属，但它保证回调边界之后对象不再可用；跨线程使用本来就超出了这个设计契约。

在事务内：

- `tx.query(...)` 能看到自己尚未提交的写入；
- 另一条连接看不到这些未提交写入；
- 在回调内再次调用 `database.transaction(...)` 会打开第二条连接和第二个独立事务；
- 内层事务不会加入外层，内层写入可能在内层提交后保留，即使外层随后回滚；
- 没有 savepoint、传播属性或声明式事务。

需要组合调用时，传递同一个 `tx`，而不是再次开启 `transaction`。

## 6. 资源与异常由数据层统一收尾

`query` 的 `PreparedStatement` 与 `ResultSet` 使用 try-with-resources；`executeUpdate` 关闭 statement；每条借出的连接都在 `finally` 或决策路径中关闭。参数绑定失败时，`JdbcCollect.prepared` 会先关闭已经创建的 statement。

所有从数据层逃出的 `SQLException` 都翻译为 `DataAccessException`，并保留原异常为 cause：

```java
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message);
    public DataAccessException(String message, Throwable cause);
}
```

典型消息会说明操作和 SQL，例如 `query failed: SELECT ...`、`update failed inside a transaction: ...`。异常消息不包含绑定参数值；应用日志仍应自行避免记录密码、完整行数据或敏感参数。

两个诚实的边界：

- 连接关闭或恢复 auto-commit 失败会被忽略，不替换已经确定的业务结果；资源问题需要数据源或监控层发现。
- mapper 自己抛出的 `RuntimeException` 不当作 JDBC 故障包装；只有 mapper 声明的 `SQLException` 进入统一翻译点。

## 7. 迁移是应用交出的版本化代码

`Migrator` 不扫描 classpath、不读取 `.sql` 文件，也不从实体生成 schema。应用构造完整迁移列表，在装配或启动阶段显式交给它：

```java
public interface Migration {
    long version();
    String description();
    void apply(Connection connection) throws SQLException;
}

public record AppliedMigration(long version, String description, Instant appliedAt) {}

public static Migrator of(Database database)
public static Migrator of(Database database, Clock clock)
public List<AppliedMigration> migrate(List<Migration> migrations)
public List<AppliedMigration> history()
```

一个最小迁移直接来自 `DatabaseExample`：

```java
new Migration() {
    @Override
    public long version() {
        return 1;
    }

    @Override
    public String description() {
        return "create notes";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS notes "
                            + "(id BIGINT PRIMARY KEY, body VARCHAR(255) NOT NULL)");
        }
    }
}
```

`apply` 接收原始 `Connection`，因为迁移需要说数据库自己的 DDL 语言。但连接仍由 `Migrator` 拥有：迁移不得提交、回滚或关闭它；自己创建的 `Statement` / `ResultSet` 应由迁移关闭。

## 8. 历史表与迁移顺序

框架创建并拥有 `barezen_migration_history`：

```text
version      BIGINT PRIMARY KEY
description  VARCHAR(512) NOT NULL
applied_at   TIMESTAMP NOT NULL
```

每次 `migrate` 的行为是：

1. 在借连接前验证列表：版本必须为正，description 不得为 null 或空白，版本不得重复。
2. 按版本升序排序，忽略调用方列表顺序。
3. 创建历史表，读取已应用版本。
4. 过滤待执行版本，拒绝小于或等于最高已应用版本的待执行版本。
5. 每个待执行版本关闭 auto-commit，执行 `apply`，插入历史行，再提交。
6. 返回本次实际应用的记录，按版本升序；全部已应用时返回空列表。

版本号可以跳跃：已有 1 时新增 4 合法；已有 10 时补执行 5 会被拒绝。正常的“旧版本都在列表中，只有新版本待执行”不会被误判为乱序。

已应用版本只按 version 跳过。框架不比较 description，也不保存 SQL 校验和；应用必须保证版本一经使用就不改号、不重用，也不修改已应用迁移的内容。否则历史无法发现这种修改。

`history()` 从数据库实时读取全部记录；新数据库没有历史表时返回空列表。默认使用 `Clock.systemUTC()`；同一次运行的历史记录共享进入 `migrate` 时取得的 `Instant`，它不是每个版本各自完成的精确时刻。

## 9. 失败后的权威是历史，不是 schema

迁移失败时：

- apply 或历史插入失败时不追加该版本；commit 响应失败时结果可能不确定，应以实读历史为准；
- 后续版本不执行；
- 已提交的早期版本保留；
- `SQLException` 包装为带版本与 description 的 `DataAccessException`，rollback 失败作为 suppressed；
- 迁移自己抛出的 `RuntimeException` 或 `Error` 原样传播，rollback 失败作为 suppressed；
- 下一次启动会重新执行未记录的失败版本。

但“回滚了”只对历史行和可回滚 DML 成立。`MigratorTest` 在 H2 上实测：一个先 `CREATE TABLE`、随后抛异常的迁移，回滚后表仍然存在，历史却没有该版本。H2 的隐式 DDL 提交使 schema 与历史可能短暂不一致。

因此迁移必须为失败重试写成幂等形式，例如 `CREATE TABLE IF NOT EXISTS`。恢复方式是依据历史重新执行并修复，不是承诺 DDL 一定消失。没有 down migration、undo script、schema diff 或校验和。

此外，一次迁移运行跨所有待执行版本共用一条连接，没有超时控制；框架不协调多个进程同时迁移。多实例部署必须由应用或部署流程保证只有一个迁移者，不能把两个实例同时启动当作受支持用法。

## 10. 运行与验证

运行数据模块全部测试：

```bash
mvn -o -B -ntp -pl framework/barezen-data test
```

运行真实 H2 示例测试：

```bash
mvn -o -B -ntp -pl examples -am \
  -Dtest=DatabaseExampleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

示例 main 依次展示迁移、提交事务、查询与失败回滚：

```text
migrated -> [AppliedMigration[version=1, description=create notes, appliedAt=...]]
notes    -> [first, second]
after rollback, rows -> 2 (the doomed insert never landed)
```

关键测试包括：

- `DatabaseTest`：CRUD、null 参数、结果顺序与异常翻译。
- `TransactionTest`：提交、检查异常、运行时异常、提交失败、回滚失败、可见性、包私有决策者、生命周期与独立嵌套事务。
- `MigratorTest`：升序、重复执行、重复版本、失败停止、H2 DDL 残留、幂等重试、乱序拒绝、历史查询与提交/回滚双重失败。
- `DatabaseLifecycleTest`：连接、statement、result set 在成功和失败路径上的关闭。
- `BarezenDataArchitectureTest`：驱动、框架反向依赖、全局状态、线程与日志边界。

## 11. 使用边界清单

- 单条写入可以用 `Database.executeUpdate`；多写入或“写入后校验”的单元工作用 `Database.transaction`。
- 仓储方法接收 `Transaction` 参数，不开启事务、不提交、不保存连接。
- SQL 值使用 `?` 参数；不要拼接用户输入。
- mapper 只映射当前行，不保存 `ResultSet`。
- 迁移列表完整、版本唯一、内容冻结，DDL 为失败重试写成幂等形式。
- 目标数据库的方言、隔离、锁和 DDL 行为要单独验证；H2 测试不能替所有产品作证。
- 连接池、备份、故障转移、审计、并发迁移和分布式事务由应用或基础设施负责。
