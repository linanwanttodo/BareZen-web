# 安全：认证、授权、会话与 TLS

## 这份文档讲什么

BareZen 的安全能力分成四层，每层只回答一个问题：

1. `Authentication`：客户端提供的凭据能否证明一个身份？
2. `Authorization`：这个已认证身份是否拥有当前路由要求的权限？
3. `SessionStore`：一个随机 token 当前是否对应一个未过期的服务端会话？
4. JDK 适配器的 TLS 配置：这个 socket 是否使用 HTTPS？

它们通过 `SecurityMiddleware.wrap(...)` 组合成普通 `Handler`，不需要修改 `Request`、`Response`、`Router` 或服务器适配器。`barezen-security` 的唯一编译依赖是 `barezen-web`；用户与认证插件位于 `barezen-plugins`，依赖方向仍然是插件依赖框架端口，而不是框架认识插件。

安全决策相关记录见 [ADR-0033](../adr/0033-security-is-a-handler-decorator.md)、[ADR-0034](../adr/0034-authentication-has-three-states.md)、[ADR-0035](../adr/0035-password-hashing-pbkdf2.md)、[ADR-0036](../adr/0036-sessions-are-opaque-and-revocable.md)、[ADR-0037](../adr/0037-status-codes-and-default-deny.md)、[ADR-0042](../adr/0042-plugins-sit-on-the-framework-never-in-it.md) 与 [ADR-0050](../adr/0050-tls-is-named-by-a-keystore-not-flagged-on.md)。

## 1. 用装饰器保护路由

中间件返回一个 `Handler`，因此可以直接放进路由表：

```java
Permission reportRead = Permission.of("report:read");

Handler protectedReport = SecurityMiddleware.wrap(
        authentication,
        authorization,
        reportRead,
        new ReportHandler());

Router router = Router.builder()
        .route(HttpMethod.GET, "/report", protectedReport)
        .build();
```

`wrap` 有三个重载：

```java
wrap(Authentication authentication, Handler delegate)
wrap(Authentication authentication, Authorization authorization, Handler delegate)
wrap(Authentication authentication, Authorization authorization,
     Permission required, Handler delegate)
```

两参数重载只要求认证，成功后允许执行；三参数重载要求 `Permission.ANY`；四参数重载询问指定权限。所有重载都拒绝 null 协作者，不会把“忘记配置”解释为放行。真正公开的路由不要包装；需要匿名访问是路由表的显式选择，不是安全策略中的特殊放行状态。

执行顺序固定为：提取凭据 → 认证 → 授权 → 调用业务 Handler。任何拒绝路径都不会调用业务 Handler。业务 Handler 自己抛出的异常仍由 JDK 适配器按普通 Handler 失败处理。

## 2. 凭据只从 Authorization 头读取

中间件读取 `Request.headers().first("Authorization")`，因此重复的 `Authorization` 字段只使用第一个。解析规则是：

- 没有字段：向 `Authentication` 传 `null`。
- 没有空格：按 scheme 为 `Bearer`、整个字段为值处理。
- 有空格：在第一个空格处拆分；scheme 是前半段，值是剩余全部文本，值可以包含后续空格或为空。

框架不读取 cookie、query 或表单来寻找凭据，也不自动做 Basic 解码。scheme 和值的比较是否忽略大小写，由 `Authentication` 实现决定；内置的 `Authentication.bearer(...)` 对两者都做精确、区分大小写的比较。

```java
Authentication authentication = Authentication.bearer(
        "Bearer", "replace-with-a-real-secret", new Principal("ada"));
```

这个便捷构造器适合测试和最小演示。它不做密码散列，也不适合把长期密码或公开示例 token 直接用于生产。请求头 `Authorization: Bearer replace-with-a-real-secret` 会得到 `ada`，错误 scheme 或错误值得到失败结果，没有凭据得到匿名结果。

`Credential` 是不可变值，`toString()` 只输出 scheme，不输出值。应用日志也不应额外记录完整 `Authorization` 头、密码或 session token。

## 3. 认证结果必须保留三态

`Authentication` 是一个函数式接口：

```java
AuthenticationResult authenticate(Credential credential);
```

`credential` 允许为 `null`，表示客户端没有提供凭据。实现必须返回非 null 的 `AuthenticationResult`，并且不能把每次请求的可变状态保存在字段中。

`AuthenticationResult` 是 sealed interface，只有三种状态：

| 状态 | 含义 | 中间件结果 |
| --- | --- | --- |
| `authenticated(principal)` | 身份已验证 | 进入授权 |
| `failure(reason)` | 提供了凭据，但验证失败 | 401 |
| `anonymous()` | 没有可接受的身份声明 | 401 |

`maybePrincipal()` 只在认证成功时有值；`isAuthenticated()` 只对成功状态返回 true。`Failure.reason` 是应用选择的诊断文本，不能包含凭据，也不会被中间件发送到客户端。

需要注意，“提供错误凭据”和“没有提供凭据”在当前中间件中都返回 401。三态的价值是让认证实现保留原因、让调用方能区分状态，而不是让 wire 暴露不同失败细节。但当前 `AuthPlugin` 并未完整保留这条契约：scheme 不匹配、未知或过期 session token 都返回 `anonymous()`，不是 `failure(reason)`；中间件仍会以 401 拒绝，因此这不是越权，却会让依赖三态做诊断的调用方失去“提供了但无效”的区分。需要该区分的应用应提供自己的 `Authentication` 实现，或先修正插件并补充对应测试。

如果 `Authentication` 或 `Authorization` 抛出 `RuntimeException`，中间件返回固定正文 `Internal Server Error` 的 500，业务 Handler 不执行。**当前实现不会自动记录它捕获的异常**；需要堆栈时，应在组件内部选择安全日志点记录，并确保日志不含密码、凭据值或 token。不要为了诊断把异常细节拼进 HTTP 响应。

## 4. 授权默认拒绝，权限按名称精确比较

`Principal` 只包含一个非空、非空白的 `name`。它不是用户表记录，也不包含角色或权限；认证实现负责验证这个名字所代表的身份。

`Permission` 同样按名称比较，且区分大小写。`Permission.ANY` 的名称是 `barezen:any`，表示“这个已认证身份是否可以进入整个路由”，不是通配所有细粒度权限。

```java
Principal ada = new Principal("ada");
Permission reportRead = Permission.of("report:read");
Permission admin = Permission.of("admin");

Authorization authorization = Authorization.of(Map.of(
        ada, Set.of(reportRead)));
```

`Authorization.of(...)` 对未列出的身份或未授予的权限返回 `DENY`。`allowAll()` 和 `denyAll()` 是显式策略，不是推荐的生产默认。`UserPlugin` 对 `Permission.ANY` 直接允许，对命名权限查询用户权限表。

中间件只询问一次当前路由的权限；复杂条件、资源归属、数据范围和多权限组合应由应用自己的 `Authorization` 实现决定。不要在一个身份验证成功后，又在 Handler 里凭注释假定权限已经检查。

## 5. 把身份作为参数交给 Handler

`SecurityContext` 是不可变的请求级值，只保存本次 `AuthenticationResult`。它不是 `ThreadLocal`，没有静态访问器，也不会跨 keep-alive 请求复用。

普通 `Handler` 不需要知道身份。需要身份的 Handler 同时实现 `Handler` 与 `SecuredHandler`：

```java
final class ReportHandler implements Handler, SecuredHandler {

    @Override
    public Response handle(Request request, Map<String, String> parameters) {
        throw new AssertionError("this handler must be registered through SecurityMiddleware");
    }

    @Override
    public Response handle(Request request, Map<String, String> parameters,
                           SecurityContext context) {
        String name = context.principal().map(Principal::name).orElse("anonymous");
        return text(200, "report for " + name);
    }
}
```

这段结构来自 [SecurityExample.java](../../examples/src/main/java/com/barezen/examples/security/SecurityExample.java)。两参数方法是注册和类型检查所需的 `Handler`；经过中间件后，只有三参数 `SecuredHandler` 方法会被调用。到达三参数方法时，认证与授权已经通过，因此正常路径上 `context.principal()` 有值。

不要在 Handler 字段中保存 `SecurityContext`，也不要为了访问身份而把 Principal 塞进路由参数。前者会跨请求泄露，后者会混淆“客户端路径”和“服务端认证结果”。

## 6. 密码使用 PBKDF2-HMAC-SHA256

`PasswordHasher` 使用 JDK 自带的 `PBKDF2WithHmacSHA256`：

- 默认 600,000 次迭代；
- 16 字节 `SecureRandom` 盐；
- 32 字节派生密钥；
- 编码格式为 `algorithm$iterations$base64Salt$base64Key`；
- `verify` 从已存储的编码值读取迭代次数，而不是强制使用当前 hasher 实例的设置。

```java
PasswordHasher hasher = PasswordHasher.standard();
String storedHash = hasher.hash("hunter2");

boolean correct = hasher.verify("hunter2", storedHash);
boolean wrong = hasher.verify("wrong", storedHash);
```

同一个密码两次哈希会得到不同结果。验证使用 `MessageDigest.isEqual` 比较派生结果；格式错误、算法不受支持、非正迭代次数、Base64 材料损坏或长度不符时返回 `false`。null 密码或 null hash 属于调用错误，会抛异常。

`PasswordHasher.of(iterations, random)` 为测试提供低迭代和可控随机源，不适合降低生产强度。空密码也会被哈希；最小长度、泄露密码检查、登录限流和失败锁定是注册与登录策略，不是 hasher 的职责。

默认迭代数提高后，旧 hash 仍能按其自身记录验证。应用若想“登录成功后重新哈希并升级旧记录”，需要自己实现这一迁移；框架没有自动完成。PBKDF2 对 GPU/ASIC 的抵抗力弱于 Argon2，这是 ADR-0035 明确接受的成本，不是文档可以隐藏的优势。

## 7. 会话签发、查找、过期与撤销

`SessionStore` 是并发安全的内存存储。`standard()` 使用系统 UTC 时钟和 `SecureRandom`；`of(clock, random)` 便于确定性地测试时间与随机源。

```java
SessionStore sessions = SessionStore.standard();
SessionStore.Session issued = sessions.create(
        new Principal("ada"), Duration.ofHours(8));

String token = issued.token().value();
Optional<SessionStore.Session> found = sessions.lookup(token);
boolean revoked = sessions.invalidate(token);
Optional<SessionStore.Session> afterRevocation = sessions.lookup(token);
```

token 是 32 字节随机数经 Base64url 无填充编码得到的字符串，不携带身份或权限。实际状态——principal 与过期时间——保存在服务端。

- `create(principal, duration)` 创建会话，duration 必须为正。
- `lookup(value)` 返回未过期会话；未知或已过期返回空。过期记录在查找时删除。
- `invalidate(value)` 立即删除记录；存在返回 true，不存在返回 false。
- `rotate(value, duration)` 删除旧记录；旧 token 不存在或已过期时返回空，未过期时才为同一 principal 创建新 token。
- `SessionToken.isExpired(now)` 在 `expiresAt <= now` 时为 true。

过期清理是惰性的：只对经过 `lookup` 的记录生效，`size()` 也可能包含尚未被查找的过期记录。`rotate` 现在也会检查到期时间：它删除过期记录并返回空，不会把过期 token 续成新会话。

`SessionToken.toString()` 只输出过期时间，不输出 token 值。存储不持久化，进程重启后全部失效，也不提供分布式共享、容量上限或刷新令牌。多实例部署需要另行设计共享存储与撤销语义。

### 用 AuthPlugin 连接用户校验与会话

`barezen-plugins` 已提供真实组合：

```java
UserPlugin users = new UserPlugin(repository);
AuthPlugin auth = new AuthPlugin(
        sessions, users, "Bearer", Duration.ofHours(8));

Optional<SessionStore.Session> login = auth.issueSession("ada", "hunter2");
String token = login.orElseThrow().token().value();

AuthenticationResult returning = auth.authenticate(new Credential("Bearer", token));
```

`UserRepository` 由 `Database` 与 `PasswordHasher` 构造；`UserPlugin.initialize()` 在启动时幂等创建用户表和权限表。认证插件只依赖 `PasswordVerifier` 端口，不读取用户表，也不认识 `UserPlugin` 具体类型。

`issueSession(username, password)` 把用户名和密码保持为两个参数，避免把 `username:password` 拼接进单值 `Credential` 后产生冒号解析错误。未知用户与错误密码都返回空，应用不应向客户端区分两者。返回请求只查 session token，不再验证密码。

相同的空返回值不等于相同的耗时。当前 `UserRepository.verifyPassword` 先执行 `find(username)`，未知用户会短路，只有已存在用户才调用 PBKDF2 验证，因此两条登录失败路径的成本不同。现有测试只固定返回值，没有做时间统计或恒定时间证明。若账户枚举 timing 属于威胁模型，应用需要在认证边界额外设计统一成本、虚拟密码验证或外部恒定时间查询策略，不能仅凭“都返回 empty”宣称已经消除 side channel。

`UserRepository` 是 `barezen-plugins` 中的 H2 示例实现，不是数据库无关的完整用户平台。`grant` 使用 H2 的 `MERGE ... KEY` 语法；权限表没有指向用户表的外键，因此可以向不存在的用户名授权。`permissionsOf` 虽然使用有序 SQL，却通过 `Set.copyOf` 返回集合，调用方不能依赖迭代顺序。用户删除、停用、改名、改密、权限撤销、用户名规范化与并发建表协调均未提供；多数据库适配与版本化用户 schema 也未证明。

签发 token 后如何交付由应用决定：可以使用 `Authorization: Bearer ...`，也可以设置 cookie。若选择 cookie，`HttpOnly`、`Secure`、`SameSite`、作用域以及 CSRF 防护都需要应用或部署层明确处理；框架没有内置 Cookie 会话绑定或 CSRF 组件。

## 8. 401、403 与 500 的固定响应

中间件生成的拒绝响应使用空 `Headers` 和 UTF-8 固定正文：

| 条件 | 状态 | 正文 | 调用业务 Handler |
| --- | --- | --- | --- |
| 匿名或认证失败 | 401 | `Unauthorized` | 否 |
| 已认证但授权拒绝 | 403 | `Forbidden` | 否 |
| 安全组件抛 `RuntimeException` | 500 | `Internal Server Error` | 否 |
| 认证与授权均通过 | — | — | 是 |

当前 401 不自动添加 `WWW-Authenticate`，这些固定响应也不添加 `Content-Type`。认证失败原因、权限细节和异常堆栈不会出现在响应正文中。应用若需要 challenge header 或更丰富的错误体，应显式设计，不能依赖中间件当前未提供的行为。

## 9. HTTPS 由 keystore 配置决定

TLS 不属于 `barezen-security`，而属于 JDK 适配器。两个配置键是：

- `barezen.http.jdk.tls.keystorePath`
- `barezen.http.jdk.tls.keystorePassword`

没有 path 或 path 为空白时，适配器保持明文 HTTP。path 存在但没有密码或密码为空时，构造适配器失败；文件不存在、密码错误或 keystore 无法读取，也在构造时失败，不会先占用端口再在启动时意外降级。

适配器使用 JDK 默认 keystore 类型与默认 TLS 参数，不提供 mTLS、协议白名单、cipher suite 选择、自动证书续期或 HTTP 到 HTTPS 的重定向。`Request` 不携带 scheme 或 `isSecure()`，因此 Handler 不能根据契约判断当前是否加密。TLS 只保护传输，不替代认证、授权或会话撤销。

TLS 配置只在适配器构造时解析一次，不监听 keystore 文件变化，也没有证书或私钥轮换流程。密码以 `String` 保存在配置快照中；加载时转为 `char[]` 后没有显式清零，因此“启动日志不打印密码”只证明日志边界，不证明秘密已从内存中擦除。现有真实套接字测试覆盖有效证书、默认不信任自签名证书、明文误入 TLS 端口、错误或缺失密码与日志脱敏，但没有覆盖私钥不可用、证书过期、链损坏、不支持的 keystore 类型或 ALPN/HTTP 2 协商。

## 10. 运行与验证

在仓库根目录运行安全模块测试：

```bash
mvn -o -B -ntp -pl framework/barezen-security test
```

运行可路由的安全示例测试：

```bash
mvn -o -B -ntp -pl examples -am \
  -Dtest=SecurityExampleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

[SecurityExampleTest.java](../../examples/src/test/java/com/barezen/examples/security/SecurityExampleTest.java) 覆盖公开路由、缺失 token、正确 token、有效身份但权限不足以及错误 token。`SecurityMiddlewareTest` 还覆盖安全组件抛异常、`SecuredHandler` 上下文交付和 keep-alive 请求间的身份隔离；`SessionStoreTest` 覆盖过期边界、撤销与轮换；`PasswordHasherTest` 覆盖编码格式、随机盐、错误密码和损坏 hash。

部署前的检查清单：

- 每个受保护路由都显式经过 `SecurityMiddleware.wrap`，公开路由不经过它。
- 401/403 的测试同时断言业务 Handler 没有被调用。
- 生产 secret 不写入源码、示例、日志或异常消息。
- 登录失败不区分未知用户与错误密码。
- 退出登录和权限紧急撤销都调用 `SessionStore.invalidate`。
- 会话过期由 `lookup` 验证；`rotate` 也会拒绝并清除过期 token。
- 浏览器会话明确设计 Cookie 属性与 CSRF 防护。
- 公网部署启用 TLS，并独立处理证书续期和加密策略。
