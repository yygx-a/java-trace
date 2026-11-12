# Java Trace

[![CI](https://github.com/yygx-a/java-trace/actions/workflows/ci.yml/badge.svg)](https://github.com/yygx-a/java-trace/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

在 IntelliJ IDEA 的 Java 运行控制台中预览完整异常，调整范围、复制并保存问题记录。日志继续滚动时，已打开的预览仍保留固定快照。

这是一个可试用的早期版本，目前实测 **IntelliJ IDEA Ultimate 2025.3.3（IU-253.31033.145）的经典 Run 控制台**。其他 IDEA 版本、Community、Debug、Terminal、测试运行器、Build 和日志文件编辑器尚未单独验证。识别规则有明确边界，不承诺识别所有 Java 日志。

## 为什么做这个插件

在 IDEA 调试 Java 项目时，一条异常可能包含很长的调用栈、多层 `Caused by` 和 `Suppressed`。控制台还在不断输出新日志，手动拖选整段异常既费时间，也容易漏掉关键内容或混入其他日志。

Java Trace 希望减少这些重复操作：从控制台定位异常，把当时的内容固定成一份可核对的预览，再根据需要调整范围、复制或保存。即使控制台继续滚动、清空或结束运行，已经打开的预览也能继续使用。

保存报错及相关解释，是为了方便之后搜索和回看类似问题。AI 解释作为可选辅助，由用户主动发送所选内容；插件主要负责整理和保留排查依据，帮助开发者把更多时间用于理解异常与验证解决办法。

## 安装

1. 在 [Releases](https://github.com/yygx-a/java-trace/releases) 下载 `idea-plugin-0.4.6.zip`，不用解压。
2. IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk…**，选择该 ZIP。
3. 按 IDEA 提示重新启动。

GitHub 的 `Source code (zip)` 是源码压缩包。要安装插件，请下载 Release 中单独附带的 `idea-plugin-*.zip`。已打包的插件包含本项目三个模块，不需要在业务项目中加依赖或修改日志配置。

## 使用

1. 正常运行自己的 Java 项目，在 **Run 控制台的异常文字上右键**。
2. 选择「预览 Java 异常」或「预览全部 Java 异常」。批量预览按输出顺序勾选，保留重复异常并跳过段间普通日志。
3. 单段预览可按物理行扩大、缩小范围；复制使用当前稳定预览。无法可靠识别时，先选中文字，再右键「手动预览所选文本」。
4. 可选勾选「附带前置 ERROR 日志」。该区域与主体分开展示；主体复制不包含这些附带日志。
5. 「保存报错…」打开本地草稿，点击「保存记录」后保存到当前项目的 `.java-trace/issues`。从 Tools →「项目问题记录」查看、搜索，或双击 `.jti` 阅读。

控制台追加、清空、循环裁剪、运行结束或源 Run 页关闭时，已打开的预览仍可使用其固定快照。源位置失效后取消高亮并显示提示；需要最新内容时重新右键预览。关闭预览或项目会释放相关资源。

## 可选 AI 解释

基础识别、复制和本地保存不需要 API Key。只有用户主动点击「询问 AI」时，才会向 DeepSeek 官方 API 发送所选异常及主动勾选的上下文；这些文本自身可能包含敏感信息，请先检查预览。

用户需自行准备 DeepSeek 账号和 Key，服务商可能收费。Key 通过 IDEA PasswordSafe 保存，不写入项目、问题记录或日志；需要可用的持久凭据存储。当前客户端直连官方接口，不继承 IDEA 全局代理，不自动重试或执行 AI 建议。详见 [数据与隐私说明](docs/privacy.md)。

## 当前识别范围

- 常见 Throwable 标题、调用帧、Caused by、Suppressed、省略帧及 JVM 循环引用。
- 部分常见 Logback/Log4j 帧尾信息、有限日志头内联标题和一致的逐行前缀。
- 有可靠事件头及合法首帧封闭的有限多行消息；未知格式和交错内容保守报告。
- 原文 UTF-16 半开区间，保留中文、emoji、LF/CRLF、前缀及原始缩进。
- 单次快照上限为 **1,000,000 UTF-16 单元 / 20,000 物理行**；超限拒绝扫描，不静默丢弃。

格式与拒绝边界见 [文本格式契约](docs/text-format-contract.md)。不能恢复已被控制台丢弃的内容；完全相同前缀下不可区分的线程交错不能保证还原。

## 开发与 CI

需要 **JDK 21**。使用 Gradle Wrapper 9.3.1；默认下载 IDEA 2025.3.3 SDK，不依赖开发者机器的安装路径。

```powershell
.\gradlew.bat :recognizer-core:check :issue-store:check :idea-plugin:check :idea-plugin:buildPlugin :idea-plugin:verifyPluginProjectConfiguration
```

macOS/Linux 使用同样参数运行 `./gradlew`。本地插件产物位于 `idea-plugin/build/distributions/`。已有目标 IDEA 安装时，可加 `-PideaHome="<IDEA安装目录>"` 复用 SDK。首次构建需要下载依赖，不能直接使用 `--offline`。

GitHub Actions 在推送、Pull Request 和手动触发时检查并打包，成功后在该次运行的 Artifacts 提供插件 ZIP 和 SHA-256 文件。CI 不自动发布 Release 或上传 JetBrains Marketplace。桌面交互需要单独手测，CI 通过不等同于所有运行控制台兼容。

当前本机检查：核心 5,491 项、固定合成语料 516 项、存储 57 项、插件 150 项通过。20 个真实渲染器生成的合成样本并非独立盲测，断言数不代表准确率。原目标 IDEA 另有单段/批量/手动、追加/清空/裁剪/源释放后的实际复制和保存核验；真实项目日志与本机证据不进入公开仓库。

[架构](docs/architecture.md) · [构建与发布](docs/build-and-release.md) · [贡献指南](CONTRIBUTING.md) · [更新记录](CHANGELOG.md)

## 许可

本项目采用 [MIT](LICENSE) 许可证，允许修改、分发和商用，需保留版权和许可声明。第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
