# 05 检查点：能 start 也能 stop

## 这一步要解决什么

前四步分别解释了状态、顺序、两阶段和回滚，但设计是否成立必须由可重复运行证明。一个看起来合理的 start 可能在并发时执行两次，一个正确的 stop 可能在第一个组件失败时漏掉其余组件，一个已安装的 JVM hook 可能在显式停机后再次触发。本章把这些风险变成测试矩阵。

检查点不是再写一套新代码，而是运行仓库已经存在的 LifecycleOrchestratorTest、LifecycleRollbackTest 和 ShutdownTest，并解释每组断言为什么存在。只有这样，下一章加入 HTTP 契约时，生命周期行为仍然是稳定的底座，而不是随适配器变化而漂移的约定。

本章也明确测试的边界。27 项绿色测试证明当前参与者生命周期行为，包括启动失败回滚后关闭 ThreadProvider，以及 JVM 关闭期间 hook 移除失败不中断清理；它不自动证明任意业务资源释放成功。源码事实、测试覆盖和文档承诺三者必须分别陈述。

## 先想清楚再写

事件日志比 mock 调用次数更接近真实时序。RecordingParticipant 在每个回调前写入 id.phase，测试清空启动事件后观察停止序列。比如正常停止只断言 b.stop、a.stop，任何额外 initialize、重复 stop 或顺序颠倒都会失败。失败注入则让指定参与者在 initialize/start/stop/abort 抛 RuntimeException，覆盖正常 happy path 看不到的分支。

并发 start 测试用四个线程和 CountDownLatch 同时调用 start，预期一个成功、三个 LifecycleException，并且 a.initialize/a.start 各出现一次。它验证的是锁覆盖“检查 NEW 到设置 STARTING”的整个过程，而不是只验证 state 是 volatile。后者不能阻止两个调用者同时通过检查。

重入 stop 测试让参与者在 initialize 内调用 orchestrator.stop()，预期得到 clean 空报告，启动继续完成。这个场景模拟外部停机线程在启动窗口发出请求：它不应该阻塞等待启动结束，也不应该与启动回滚同时修改 entered。停止责任明确留给启动路径。

hook 测试使用 CapturingShutdownHookRegistry 记录 add/remove 并手动 fire。这样可以验证“成功启动后安装、显式停止时移除、进程终止路径调用同一个 stop、失败启动不安装”，而不会真的向运行 Surefire 的 JVM 注册可能影响测试进程退出的钩子。ShutdownHookRegistry.jvm() 只是生产默认实现，不是公开扩展 API。

线程提供者顺序测试注入一个 observing ThreadProvider，在参与者 stop 中断言 providerShutDown 仍为 false，最终事件必须是 b.stop、a.stop、provider.close。这个断言来自 ADR-0011：参与者在清理时仍可能需要提交任务，所以不能先关闭线程工厂。启动失败测试还断言 b.stop、a.stop、provider.close，并确认 FAILED 后 stop 不会重复关闭。

## 动手

- [LifecycleOrchestratorTest.java](../../framework/barezen-core/src/test/java/com/barezen/core/kernel/LifecycleOrchestratorTest.java)：11 项，覆盖两阶段顺序、状态、幂等、并发、重入和提供者关闭顺序。
- [LifecycleRollbackTest.java](../../framework/barezen-core/src/test/java/com/barezen/core/kernel/LifecycleRollbackTest.java)：8 项，覆盖 abort/stop 选择、互斥、原始异常、多失败回滚与启动失败关闭 provider。
- [ShutdownTest.java](../../framework/barezen-core/src/test/java/com/barezen/core/kernel/ShutdownTest.java)：8 项，覆盖最佳努力停机、报告、hook 安装/移除/触发、移除失败继续清理与失败启动不安装。
- [RecordingParticipant.java](../../framework/barezen-core/src/test/java/com/barezen/core/kernel/RecordingParticipant.java)：共享事件日志和失败注入，只存在于测试源码。
- [ShutdownHookRegistry.java](../../framework/barezen-core/src/main/java/com/barezen/core/kernel/ShutdownHookRegistry.java)：包私有替换点，避免单元测试操作真实测试 JVM 的 hook。
- [LifecycleOrchestrator.java:160](../../framework/barezen-core/src/main/java/com/barezen/core/kernel/LifecycleOrchestrator.java#L160)：正常 stop 的实际入口与线程提供者关闭位置。
- [ADR-0005](../../docs/adr/0005-shutdown-is-best-effort-and-reported.md#L15)：所有参与者均尝试，异常转为报告。

`2670984` 建立第一组生命周期测试；后续 `e0bc31b` 与 `e2c9902` 调整 builder 和装配时也维护这些测试；`6aac12f` 删除公开 threadKind 后，测试改为从应用位置解析 ThreadPolicy。这个变化不是“测试迁就实现”，而是测试不再依赖一个只服务于测试的公开 getter。

## 常见的第一次写错

### 检查覆盖与未覆盖边界

| 行为 | 是否由本检查点证明 |
| --- | --- |
| 全部 initialize 早于全部 start | 是，事件序列断言 |
| 注册顺序启动、逆序停止 | 是 |
| initialize 失败 abort，start 失败 stop | 是 |
| 回滚不会二次执行 | 是，FAILED 后 stop 无新事件 |
| 清理失败不覆盖启动原因 | 是，cause 与 rollbackFailures |
| 正常停止关闭 owned ThreadProvider | 是，观察者实现 |
| 启动失败关闭 owned ThreadProvider | 是，回滚后 provider.close 且 stop 不重复 |
| hook 移除失败不中断停机 | 是，参与者与 provider 仍按顺序释放 |
| 任意业务 stop 都真正释放资源 | 否，框架只能尝试并报告 |
| 多实例共享生命周期状态 | 否，orchestrator 是单实例状态机 |

读检查点结果时还要把“测试总数”和“测试类 XML 数量”分开。JUnit 嵌套类会在 Surefire 中形成独立 XML，外层类文件可能显示零项；必须按类名前缀聚合，才能得到这里的 11、8、8。若某个类没有运行，应先看 `-Dtest` 是否连嵌套类一起选中，再检查 XML 中的 failures 与 errors，不能把“文件存在”当成“断言执行”。反过来，总数正确也不代表每个行为都被覆盖，上一表列出的“否”仍然是源码审阅后留下的边界。

失败诊断也应从事件序列开始。顺序不符时，先找到第一个偏离预期的事件，再判断是实现重复回调、注册顺序变化，还是清理阶段选错；不要只把异常消息复制到问题描述。停机报告非 clean 时，要逐个查看 participant id 与 cause，并确认后续参与者仍被尝试。27 项全部通过证明的是这些可重复场景，而不是任意线程交错、任意业务资源或所有宿主停机策略。

对新增断言还应做反向检查：在隔离实验中故意把 releaseEntered 的 removeLast 改成 removeFirst，观察逆序测试是否变红；故意把 initialize 失败改成 stop，观察 abort 互斥测试是否变红。不要在当前仓库或用户 WIP 上做破坏性修改；这里说明的是验证方法，不是本章实际执行过的破坏实验。

## 怎么确认这一步没造错

在仓库根目录执行：

```bash
mvn -o -B -ntp -pl framework/barezen-core -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=LifecycleOrchestratorTest,LifecycleRollbackTest,ShutdownTest
```

参数含义需要清楚：`-o` 使用本地 Maven 缓存，不联网下载；`-B -ntp` 适合非交互日志；`-pl ... -am` 构建 core 及所需父模块；`-Dtest=...` 限定三类测试；`failIfNoSpecifiedTests=false` 让没有这些测试的父模块不被误判失败，不会把 core 的测试失败变成成功。

本章写作时实际输出：

```text
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

可逐组复核：

| 测试类 | 数量 | 关键证据 |
| --- | ---: | --- |
| LifecycleOrchestratorTest | 11 | 两阶段顺序、六态转换、并发 start、重入 stop、provider 最后关闭 |
| LifecycleRollbackTest | 8 | initialize abort、start stop、互斥、entered 清空、原始异常与 suppressed、启动失败关闭 provider |
| ShutdownTest | 8 | 所有参与者均尝试、stop 报告、hook 生命周期、hook 移除失败继续清理与失败启动不安装 |
| 合计 | 27 | 0 failures，0 errors |

如果只想验证顺序与回滚，可去掉 ShutdownTest；但第 3 章交付应以完整 27 项为准。后续改动若导致失败，先读具体断言和事件序列，不要把失败统称为“生命周期不稳定”。其他模块的失败不应通过修改本模块源码来掩盖，也不影响本章单独运行。

## 此刻的代码长什么样

第 3 章完成：你有六态状态机、固定注册顺序、两阶段全局启动、参与者级失败回滚、正常停机报告和一套 27 项通过的检查点。下一章开始定义 HTTP 契约，先让 Request、Response 与 Headers 不依赖 JDK 或 Servlet，再把生命周期中的 HTTP 参与者接到真实适配器上。
