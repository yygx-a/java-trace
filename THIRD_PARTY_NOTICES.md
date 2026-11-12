# 第三方组件说明

本项目自身代码和文档采用根目录的MIT许可证；该许可不改变第三方组件的许可。

- **Gradle Wrapper 9.3.1**：仓库包含官方Wrapper脚本与JAR，采用Apache License 2.0；JAR内保留`META-INF/LICENSE`。Gradle发行版由Wrapper另行下载，并使用`distributionSha256Sum`校验。见[Gradle许可](https://github.com/gradle/gradle/blob/v9.3.1/LICENSE)。
- **IntelliJ Platform Gradle Plugin 2.12.0 / IDEA SDK**：构建时解析，IDEA平台本身不放入插件ZIP；使用和下载受各自许可约束。目标IDEA提供的Gson由平台复用，不在本插件重复打包。
- **JDK / Logback / Log4j**：JDK是开发/运行工具；日志框架只用于开发阶段生成合成语料，不成为识别核心或插件的运行依赖，也不随本插件ZIP分发。合成文本与生成器由本项目编写，未复制框架实现代码。
- **Filebeat Java multiline文档**：仅借鉴事件头作为有限边界证据的概念，未复制第三方代码/正则、未引入Filebeat/ELK。参考链接位于[格式契约](docs/text-format-contract.md)。

插件ZIP只分发本项目的`idea-plugin`、`recognizer-core`和`issue-store`模块JAR；各JAR附带本项目MIT许可证。
