# 第 9 章第 1 步：密码哈希为何用 PBKDF2

## 这一步要解决什么

HTTP 框架迟早会遇到“验证用户名与密码”的需求，但框架不应该顺便发明一套用户表。此刻要先解决的是更小、更基础的问题：应用把用户提交的密码交给谁，才能只保存不可逆的验证材料，并且在数据库泄露后不让攻击者像计算普通文件摘要一样高速穷举？`7d913bf` 新增的 `PasswordHasher` 就是这个答案，ADR-0035 是它的设计论证。

这里的目标不是“让登录能用”，而是把密码从请求中的临时字符串变成可存储、可升级、可核验的编码值。它不决定密码长度、是否允许空密码、何时锁定账户，也不提供用户持久化；这些属于应用注册与认证策略。把这些边界划清后，哈希器才能保持为一个无第三方依赖、可在 JDK 17 字节码基线上运行的具体安全默认值。

## 先想清楚再写

最常见的第一次实现是 `sha256(password.getBytes())`。它满足“数据库里看不到明文”，却违反密码存储最重要的假设：攻击者已经拿到数据库，可以离线尝试几十亿个候选值。普通 SHA-256 本来就为高速处理大量数据而设计，这种速度放在密码上恰好帮助攻击者。加一个固定盐只能阻止预计算彩虹表，不能阻止针对你这个盐的批量穷举；让盐等于用户名还会让同用户改密码前后的材料保持可关联。

第二次容易犯的错是把“慢一点”理解成“多哈希几次”。PBKDF2 的价值是使用带密钥的伪随机函数反复派生，并把迭代次数写进结果。应用以后可以提高新密码的成本，旧密码仍按它自己的次数验证；成功登录时再重写哈希，迁移便不需要一次性重置所有账户。若次数只存在于当前配置，旧结果与新配置立刻不兼容；若盐不随机，两个相同密码会得到相同编码，复用密码也会暴露。

还要提前决定畸形数据属于谁的问题。用户输错密码应得到 `false`，数据库中的旧格式或损坏字段也不应该把一次普通登录变成 500。可是“所有异常都返回 false”同样危险：JVM 缺少必需算法是服务器基础设施故障，不应该伪装成密码错误。因此解析层要返回失败，派生层遇到平台能力缺失要抛 `IllegalStateException`，两条路径不能混为一谈。ADR-0035 写得比较概括，实际代码对负数迭代等少数畸形值仍有未覆盖边界，本章检查点会单独列出，不用文档替源码掩盖它。

## 动手

先固定生产参数，再提供测试入口。[PasswordHasher.java:34](../../framework/barezen-security/src/main/java/com/barezen/security/PasswordHasher.java#L34) 定义算法 `PBKDF2WithHmacSHA256`，默认六十万次迭代，盐十六字节，派生密钥三十二字节。`standard()` 创建新的 `SecureRandom`；`of(int, SecureRandom)` 允许测试使用一千次迭代和固定随机源，但拒绝非正次数。不要把测试计数做成默认值，也不要让调用者传一个普通 `Random` 充当安全随机源。

```java
public static PasswordHasher standard() {
  return new PasswordHasher(DEFAULT_ITERATIONS, new SecureRandom());
}

public static PasswordHasher of(int iterations, SecureRandom random) {
  if (iterations <= 0) {
    throw new IllegalArgumentException("iterations must be positive");
  }
  Objects.requireNonNull(random, "the random source");
  return new PasswordHasher(iterations, random);
}
```

接着实现 `hash(String)`：每次生成新盐，调用 JDK 的 `SecretKeyFactory` 派生三十二字节，然后保存四段文本。源码在 [PasswordHasher.java:85](../../framework/barezen-security/src/main/java/com/barezen/security/PasswordHasher.java#L85)，格式是 `算法$次数$Base64盐$Base64密钥`。Base64 使用标准编码器，因为这是数据库编码而不是 URL；空密码也会被哈希，强度策略不应偷偷塞进这个类。

```java
byte[] salt = new byte[SALT_LENGTH];
random.nextBytes(salt);
byte[] derived = derive(password, salt, iterations);
return ALGORITHM + "$" + iterations + "$"
    + Base64.getEncoder().encodeToString(salt) + "$"
    + Base64.getEncoder().encodeToString(derived);
```

最后实现 `verify(String, String)`。先按四个字段拆分，核对算法、解析次数并拒绝非正数，再解码盐与期望密钥、检查长度。只有这些结构检查通过，才使用哈希内记录的次数重新派生；比较使用 `MessageDigest.isEqual`，不要用字符串 `equals` 暴露前缀比较的时序差异。解析失败返回 `false`，而不是把损坏记录的内容拼进异常。真正的派生调用位于 [PasswordHasher.java:137](../../framework/barezen-security/src/main/java/com/barezen/security/PasswordHasher.java#L137)，使用 `PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH * 8)`。

```java
if (parts.length != 4 || !ALGORITHM.equals(parts[0])) {
  return false;
}
// 解析次数与 Base64；次数非正或字段长度不是 16/32 时返回 false。
byte[] actual = derive(password, salt, iterations);
return MessageDigest.isEqual(actual, expected);
```

`toString()` 只输出算法与实例次数，不输出密码或任何一次生成结果。模块还有一条 ArchUnit 规则禁止主源码依赖 slf4j 或 `java.util.logging`，所以“不打印秘密”不只依赖程序员记得脱敏。

## 常见的第一次写错

1. **错误：`MessageDigest.getInstance("SHA-256").digest(password)` 当密码哈希。** 正确：使用 `PBKDF2WithHmacSHA256` 与高迭代次数。普通摘要是文档完整性工具，不是慢口令派生函数。
2. **错误：所有用户共用一个固定盐。** 正确：每次 `hash` 都从 `SecureRandom` 获取新盐，并把它和结果一起保存；测试里的固定盐只能出现在 `of(...)` 的测试随机源中。
3. **错误：验证时使用 `standard()` 的当前迭代数。** 正确：从 `encodedHash` 的第二段读取次数，否则旧记录无法跨版本验证。
4. **错误：哈希解析失败直接抛异常，或让零/负迭代次数进入 `PBEKeySpec`。** 正确：普通结构错误与非正次数都返回 `false`，内部算法缺失才作为服务端故障抛出；任何消息都不携带密码、盐或完整摘要。
5. **错误：把 `of(1, random)` 作为方便的生产快捷方式。** 正确：生产使用 `standard()`，低次数只服务测试；同时把空密码与最小长度留给注册策略，避免哈希器承担相互冲突的职责。

## 怎么确认这一步没造错

运行十六项哈希测试，覆盖格式、随机盐、正确与错误密码、损坏字段、非正迭代、跨迭代数验证、构造拒绝和脱敏：

```bash
mvn -o -B -ntp -pl framework/barezen-security -am test \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PasswordHasherTest
```

预期 `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`，最终 `BUILD SUCCESS`；`PasswordHasherTest$Hash` 为 3 项、`$Verify` 为 9 项、`$Construction` 为 4 项。上游 `ClassMajorContractTest` 会额外报告 `unique-majors=[61]`，说明安全产物仍符合字节码 17 基线，而不是绑定构建机 JDK。

测试通过不等于每个恶意编码都安全。当前 `verify` 会拒绝非数字、零和负数的迭代字段，避免它们进入 `PBEKeySpec` 后抛出 `IllegalArgumentException`。这修复了损坏记录的异常路径，但没有为迭代次数设置上限，也没有消除账户枚举 timing；这两类边界仍需独立威胁建模与验证。

## 此刻的代码长什么样

`PasswordHasher` 是一个 final 类，只有 `standard()`、`of(...)`、`hash(...)`、`verify(...)` 四个公开成员。它已经能够生成和验证自描述材料，但没有连接数据库，也不知道用户名。应用可以把 `hash` 放在注册边界，把 `verify` 放在自定义 `Authentication` 中；是否成功、何时升级旧哈希、失败几次后锁定，都还没有决定。

下一步要解决“验证成功后如何识别后续请求”。如果每次请求都重新提交密码，安全边界就退化成了明文凭据的频繁传输；如果让客户端 token 自带全部身份和权限，撤销又会变成新的难题。因此需要一种不透明值，它本身不说明任何身份，只允许服务端查找到一条可删除、会过期的会话记录。
