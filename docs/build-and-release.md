# 构建、CI与发布

## 固定工具版本

| 工具 | 当前版本 |
| --- | --- |
| 开发JDK / Java release | 21 |
| Gradle Wrapper | 9.3.1，发行版SHA-256已固定 |
| IntelliJ Platform Gradle Plugin | 2.12.0 |
| 目标SDK | IntelliJ IDEA Ultimate 2025.3.3 / IU-253.31033.145 |
| 插件 | 0.4.6 |

使用JDK21运行Wrapper。默认从构建文件声明的官方仓库下载SDK；使用本机SDK时显式传`-PideaHome="<IDEA安装目录>"`。不要把个人安装路径写入仓库。首次解析需要网络，之后有完整缓存才可加`--offline`。

```powershell
.\gradlew.bat --console=plain :recognizer-core:check :issue-store:check :idea-plugin:check :idea-plugin:buildPlugin :idea-plugin:verifyPluginProjectConfiguration
```

macOS/Linux使用`./gradlew`执行相同任务。插件产物是`idea-plugin/build/distributions/idea-plugin-0.4.6.zip`，包含三个本项目JAR及MIT许可。IDEA平台与JDK不打进ZIP。

`check`使用普通JDK回归程序；Gradle空测试框架任务被禁用，不能只看`test SKIPPED`判断没有验证。核心/固定语料/存储/插件各回归由check任务依赖执行。HTTP回归只连接本机环回合成服务，不读取真实凭据。配置检查的until-build建议为已知提示，当前不因此宣称更宽IDEA兼容性。

## CI

`.github/workflows/ci.yml`在push、pull_request和手动触发时运行。使用Windows2025 runner、Temurin21及项目Wrapper，自动下载目标SDK并运行上述检查。官方Actions固定至经核对的完整commit SHA，工作流仅有contents:read权限，未配置DeepSeek或Marketplace秘密。PR不会自动发布。

通过后，在[Actions](https://github.com/yygx-a/java-trace/actions)打开该次CI，下载`java-trace-<commit>` artifact。解开CI artifact外层压缩包后，内含真正的插件ZIP和`SHA256SUMS.txt`；安装插件时无需再解开插件ZIP。默认保留14天，长期分发请使用Release。

检查失败时先查看失败任务输出，修复后正常提交；不靠跳过回归制造通过。自动CI不能代替IDEA经典Run中的右键位置、源码链接、稳定预览、循环裁剪、线程和生命周期实测。

## 维护者显式发布

1. 确认版本号、CHANGELOG、MIT许可和公开范围；运行check、打包及目标IDEA手测。
2. 提交源码并等待对应commit的真实CI通过。
3. 优先下载该commit的成功CI产物，以这一份ZIP作为Release附件，确保发布物对应被检查的源码；保留其SHA-256。
4. 创建与版本一致的tag/Release，附ZIP、`SHA256SUMS.txt`和安装/兼容性说明。GitHub自动生成的Source code ZIP仅供开发，不能作为IDEA插件安装。
5. 从Release下载一次附件，逐字节/SHA-256核验与成功CI产物一致，再交接安装。

Release发布由维护者主动执行，不随每次push自动发生。JetBrains Marketplace属于另行授权、元数据/许可/兼容性检查和审核的发布流程，当前工作流不包含该动作。

`build/`、本地问题记录、密码库、真实日志和本机执行计划均被忽略。合成测试`.log`仅在两个明确fixture目录允许跟踪，更新预期应依据人工语义而非识别器当前输出。

参考：[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)、[Gradle Actions](https://github.com/gradle/actions)、[JetBrains本地安装](https://www.jetbrains.com/help/idea/managing-plugins.html)。
