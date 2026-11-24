# 为什么不用 --enable-preview 与内部模块

## 这一步要解决什么

虚拟线程 API 不在 JDK 17 的编译符号表中，但项目又希望在 JDK 21+ 使用它。这一小步专门解决这个矛盾：怎样使用新能力，同时不提高字节码基线、不拆分 JDK 模块，也不把兼容代码扩散到整个仓库。

## 先想清楚再写

一个诱人的方案是打开 `--enable-preview`，直接编译新 API。preview 的语义是“当前 JDK 暂时提供、未来可能改变”，它通常还要求 `--enable-preview` 才能运行对应字节码。把框架构建在 preview 上，等于让消费者同时接受语言与 API 的不稳定承诺；升级 JDK 时，同一个 jar 可能因为 preview 版本不匹配而无法加载。

直接依赖 `jdk.*` 内部模块同样危险。内部类型不受公共兼容承诺保护，模块导出和封装会随 JDK 调整，消费者还可能必须添加 `--add-exports`。这些启动参数看起来只占一行，却把框架的部署要求扩散到每个应用，也让测试环境与生产环境更容易产生差异。

复制一套 barezen-jdk21 源码可以解决编译问题，却会把公共逻辑分成两份。一次 bug 修复需要改两处，包名、资源和测试也容易漂移；消费者还得选择正确 artifact。多版本 jar 能在一个 jar 中放不同类，但类和资源选择规则更复杂，还会掩盖“到底加载了哪一份”的运行时事实。

MethodHandle 方案把直接符号引用变成字符串查找。代码仍以 release 17 编译，只在运行到 JDK 21+ 时查找 `Executors.newVirtualThreadPerTaskExecutor`。如果方法不存在、不可访问或发生 LinkageError，探测返回不可用，而不是让类初始化直接炸掉。这个选择不是零成本：反射式绑定失败更晚，参数类型写错也只会在运行期暴露；因此探测必须集中、包私有，并由真实能力测试覆盖。

集中还有一个架构目的。若每个模块都写自己的 MethodHandle，所谓“能力模型”很快会失去唯一口径。仓库用 ArchUnit 禁止 runtime 包之外使用 `java.lang.invoke..`，从结构上保证新增兼容分支必须先修改这个受审边界。没有消费者的新 JDK 功能不能靠“顺手支持一下”进入核心。

## 动手

真实桥接器是 `com.barezen.core.runtime.VirtualThreadProbe`，包私有。它保存一个静态 final MethodHandle，并只在 feature 至少 21 时解析：

```java
if (Runtime.version().feature() < 21) {
  return null;
}
try {
  return MethodHandles.lookup()
      .findStatic(Executors.class, "newVirtualThreadPerTaskExecutor",
          MethodType.methodType(ExecutorService.class));
} catch (NoSuchMethodException | IllegalAccessException | LinkageError e) {
  return null;
}
```

支持判断就是 handle 是否非空：

```java
static boolean supported() {
  return PER_TASK_EXECUTOR != null;
}
```

创建执行器时直接 invoke，并把检查异常包装为 `IllegalStateException`。`RuntimeException` 与 `Error` 原样重抛，不把所有失败都压成同一种诊断。第一次实现时，先写“JDK 17 返回不支持”和“当前可用时创建成功”两个测试，再考虑 provider 生命周期。

### 提交切片：一个 probe，一次解析，一份失败策略

这条兼容路径在 `2670984` 已存在，完整文件是 [VirtualThreadProbe.java](../../framework/barezen-core/src/main/java/com/barezen/core/runtime/VirtualThreadProbe.java)。它是 `final` 且包私有，`PER_TASK_EXECUTOR` 在类初始化时解析一次。选择静态缓存而不是每次创建执行器都查找，原因是能力在这个 JVM 生命周期内不会变化；反复查找既浪费，也可能让不同调用看到不一致结果。

解析顺序也有明确边界：先看 `Runtime.version().feature() < 21`，低于 21 立即返回 null；只有版本足够才进行 MethodHandle 查找。这样 JDK 17 不会触碰一个不存在的方法名。查找失败捕获 `NoSuchMethodException`、`IllegalAccessException` 和 `LinkageError`，统一返回不可用。返回 null 不是掩盖错误，而是把“这个可选能力不存在”变成 ThreadPolicy 可以处理的事实。

真正调用发生在 `newPerTaskExecutor()`。`invoke()` 声明的是受检异常，桥接层必须把它包装成 `IllegalStateException`；但 `RuntimeException` 和 `Error` 要先原样重抛，避免把 JVM 级错误伪装成普通启动问题。公共 `RuntimeCapabilities` 又在前面检查 `virtualThreads`，所以一个明确构造为不支持的快照不会进入 probe。两层检查分别保护“伪造能力状态”和“探测后仍创建失败”，不是重复代码。

对应测试都在 [ThreadProvidersTest.java](../../framework/barezen-core/src/test/java/com/barezen/core/concurrent/ThreadProvidersTest.java)。`Detection` 嵌套类中的 `detectReportsTheActualRuntime` 对照真实 `Runtime.version().feature()`，`virtualExecutorCreationFollowsTheReportedFlag` 验证 false 快照拒绝创建，`executorFromADetectedRuntimeIsUsable` 只在真实探测可用时提交任务并关闭执行器。JDK 17 上最后一项直接返回，不用假造一个虚拟线程实现；JDK 21+ 上它才验证 invoke 后的执行器确实能工作。

这也是为什么不把 probe 变成公开工具类。若消费者可以绕过 `RuntimeCapabilities` 直接调用它，能力快照就失去唯一所有权；若把 MethodHandle 暴露出去，其他模块又能各自发明缓存、异常和生命周期策略。当前公开面只有三字段快照和一个实例化方法，桥接细节留在包内。ADR-0010 还明确禁止 `ScopedValue` 与 `StructuredTaskScope`，说明这个 seam 不是“以后所有 JDK 21 API 都从这儿进”的通用后门。

## 常见的第一次写错

- **错误：使用 `--enable-preview` 绕过 release。** 正确：保持 release 17，把新 API 作为运行期能力。
- **错误：直接 import 内部虚拟线程类型。** 正确：只查找公共 Executors 的静态方法，不引用内部实现。
- **错误：每个模块各自探测。** 正确：集中在 runtime 包，并用 ArchUnit 限制其他位置。
- **错误：查找失败就抛异常终止启动。** 正确：探测返回不可用，让 AUTO 回退、显式 VIRTUAL 明确失败。
- **错误：忽略 LinkageError。** 正确：自定义或裁剪 JRE 即使版本够高，也可能没有目标 API。

## 怎么确认这一步没造错

运行真实能力与架构测试：

```bash
mvn -o -B -ntp -pl framework/barezen-core -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ThreadProvidersTest,BarezenCoreArchitectureTest,ClassMajorContractTest
```

预期 `BUILD SUCCESS`。`detectReportsTheActualRuntime` 核对真实版本与能力标志，`virtualExecutorCreationFollowsTheReportedFlag` 覆盖创建路径；架构规则 `newer_jdk_probing_is_confined_to_runtime` 与 `scoped_value_and_structured_concurrency_are_not_used` 拒绝额外探测和未经批准的 JDK 21 API。

声明检查与产物检查仍要分别运行：

```bash
scripts/check-jdk-baseline.sh
scripts/check-class-major.sh
```

两者都应退出零；后者只有在真实 class 文件存在时才有意义。

## 此刻的代码长什么样

核心现在能用单一 release 17 产物在 JDK 21+ 创建虚拟线程执行器，同时在旧运行时安全地报告不支持。桥接器只有一个，公共入口仍通过 RuntimeCapabilities 暴露事实，不暴露 MethodHandle 细节。这个 seam 很小，却不是免费魔法：它用受控的晚绑定换取可移植性。

下一步把“核心不应知道 HTTP、SQL 或容器”变成模块结构。只要依赖图画对，许多错误会在编译前就被排除；ArchUnit 再检查那些编译成功但设计上不应发生的关系。这样，后面的生命周期代码可以专心管理对象，而不用抵御整个上层框架对 core 的反向渗透。
