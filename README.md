# BareZen-web

（此项目正在逐渐完善打磨中，拥有良好的开发骨架）

BareZen-web 是一个轻量、模块化、插件化的 Java Web 框架，包名 `com.barezen`。它不依赖 Spring，核心只定义 HTTP 契约、路由、生命周期与插件 SPI；服务器、安全、数据访问、OpenAPI、静态资源等能力都由独立模块提供。

设计原则：内核简单、模块可组合、插件即插即用、默认安全、面向扩展。

## 项目特性

- 不依赖 Spring，核心编译依赖仅 SLF4J API
- HTTP 契约不绑定具体服务器：内置 JDK HttpServer 适配器，也可通过 Servlet 桥接嵌入 Tomcat、Jetty 等容器
- 插件 SPI 与 ServiceLoader 发现，路由可以由插件在装配阶段贡献
- 安全模块：PBKDF2-HMAC-SHA256 密码哈希、可撤销的不透明会话、401 与 403 边界、浏览器响应安全头装饰器
- 数据模块：`Database(DataSource)`、显式事务、版本化迁移，不绑定 JDBC 驱动
- OpenAPI 文档生成、磁盘与 classpath 静态内容服务、基于密钥库的 TLS、TestKit 集成测试工具
- 8 个可运行示例，仅使用公开 API
- 50 余篇 ADR 记录架构决策与边界

## 模块

| 模块 | 说明 |
|:---|:---|
| `framework/barezen-core` | 生命周期、线程、配置、显式依赖装配、事件总线、插件 SPI |
| `framework/barezen-web` | HTTP 契约、路由、处理器与服务器适配器接口 |
| `framework/barezen-http-jdk` | 基于 JDK HttpServer 的 HTTP 运行时 |
| `framework/barezen-http-servlet` | 在 Servlet 容器内复用同一路由 |
| `framework/barezen-security` | 认证、授权、密码哈希与会话 |
| `framework/barezen-data` | DataSource 边界、事务与迁移 |
| `framework/barezen-content` | 静态文件、classpath 资源、条件请求与单范围 GET |
| `framework/barezen-plugins` | 认证、用户与 OpenAPI 插件 |
| `framework/barezen-testkit` | 真实端口集成测试工具 |
| `examples` | 8 个可运行示例 |
| `app` | 控制台示例应用 |

## 快速开始

环境要求：JDK 17 及以上，Maven 3.9 及以上（当前构建在 Maven 3.9.12 上验证）。

```bash
mvn verify
```

构建会运行单元测试、ArchUnit 架构规则与覆盖率门禁。示例程序位于 `examples/`，可直接运行各自的 `main()`。

## 文档

- 框架建造教程：[tutorial-docs/README.md](tutorial-docs/README.md)
- 项目定位与适用边界：[docs/POSITIONING.md](docs/POSITIONING.md)
- HTTP 契约与 Handler 指南：[docs/guide/web-handlers.md](docs/guide/web-handlers.md)
- 安全认证与会话指南：[docs/guide/security.md](docs/guide/security.md)
- 数据访问与事务指南：[docs/guide/data.md](docs/guide/data.md)
- 配置解析与显式装配指南：[docs/guide/configuration-assembly.md](docs/guide/configuration-assembly.md)
- 架构决策记录：[docs/adr/README.md](docs/adr/README.md)
- 全部 ADR 中文索引：[docs/adr-index-zh.md](docs/adr-index-zh.md)
- 插件编写指南：[docs/guide/authoring-a-plugin.md](docs/guide/authoring-a-plugin.md)
- TestKit 快速开始：[docs/testkit/quick-start.md](docs/testkit/quick-start.md)
- 示例说明：[docs/examples/README.md](docs/examples/README.md)
- 项目站点：`site/`

## 开发状态

框架的核心契约、HTTP 运行时、安全、数据、插件、OpenAPI 与测试工具已经实现，后续继续完善边界与文档。
