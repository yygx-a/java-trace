# 架构与公共契约

当前可试用版本为0.4.6。范围是本地Java文本异常识别、IDEA经典Run预览/调整/复制和项目内记录；AI解释为用户主动选择的可选功能。无遥测、自动采集或自动执行建议。

```text
idea-plugin ──> recognizer-core
     └────────> issue-store
```

- `recognizer-core`：普通Java；原文快照→LineIndex原文行坐标→LogFormatAdapter有限格式视图→LineClassifier行标签→TraceAssembler异常块/分支→锚点/批量结果。颜色或超链接不作为证据。
- `idea-plugin`：捕获右键位置和快照，后台解析；界面线程管理窗口及源高亮。固定快照与源位置拥有不同生命周期，新输出只解绑位置；窗口/项目关闭释放资源。手动选区明确标注未识别。
- `issue-store`：纯JDK本地文件记录，临时文件/原子替换、跨进程锁与版本冲突保护。支持旧JTI1和含可选AI数据的JTI2。

## 识别入口

`JavaExceptionRecognizer.recognize(String originalText, int anchorOffset)` 识别锚点所属异常；带两个截断标志的重载报告快照缺失。`recognizeAll(String originalText, boolean startTruncated, boolean endTruncated)` 按顺序保留全部候选与重复事件。

偏移均为Java String的UTF-16索引，范围为`[startOffset, endOffset)`，可直接substring。原文、CRLF、ANSI及前缀不被修改；分类视图规范化不能改变返回坐标。锚点在换行符上归前一行，文末归最后实际行；null/越界参数为编程错误。

`RecognitionResult`区分FOUND/UNCERTAIN/NOT_FOUND，主体与可选前置ERROR上下文分开；reasons为结构证据，warnings为不完整/边界/上限问题，notes为无帧分支等内容观察。UNCERTAIN可没有安全范围。FOUND仅表示当前规则未触发警告，不代表数学上的正确性。

扫描上限为100万UTF-16单元或2万物理行，超限拒绝；目前不做滑动局部扫描。未知前缀/不可解释消息/交错不能默默吞并。具体适配边界见[格式契约](text-format-contract.md)。

## 证据与限制

固定测试语料中20例由实际JDK/Logback/Log4j渲染器生成，分开发12例与预留验收8例；标注由生产样本的已知片段与人工语义指定。生成器在`docs/smoke/RuntimeCorpusGenerator.java`，日常测试仅读取固定文本，不需要日志框架或网络。样本不是独立贡献者盲测，断言数/锚点组合不能作为准确率。

`docs/smoke/ConsoleProbeSample.java`为合成Run交互样例，可在IDEA以Java Application运行。它只用于底层回归，不能代替真实应用试用证据。本机真实项目的日志、截图、机器路径与历史操作报告不随公开仓库发布。
