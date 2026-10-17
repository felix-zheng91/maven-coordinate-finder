# Maven Coordinate Finder

[![JetBrains Marketplace](https://img.shields.io/badge/Marketplace-Maven%20Coordinate%20Finder-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/35213-maven-coordinate-finder)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/35213)](https://plugins.jetbrains.com/plugin/35213-maven-coordinate-finder)

一个 IntelliJ IDEA 插件：在 IDE 内搜索 Maven 依赖坐标、列出全部版本、一键复制可粘贴的 dependency 片段。

仓库：<https://github.com/felix-zheng91/maven-coordinate-finder>

## 安装

在 IDE 中打开 `Settings → Plugins → Marketplace`，搜索 **Maven Coordinate Finder**，
或从 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35213-maven-coordinate-finder) 安装。

在线介绍页（含官方安装按钮）：<https://felix-zheng91.github.io/maven-coordinate-finder/>

**它不做什么**：不修改 `pom.xml` / `build.gradle`，不做依赖树分析、冲突检测。只负责「搜坐标 → 列版本 → 复制」。

许可证：[Apache-2.0](LICENSE)。

## 环境要求

| 项 | 版本 |
|---|---|
| IntelliJ IDEA | 2026.1+（build 261+），无 `untilBuild` 上限 |
| JDK（运行 Gradle） | 25 |
| JDK（编译 toolchain） | 21（与 2026.1 平台字节码对齐） |
| Kotlin | 2.3.20（与 2026.1 捆绑 stdlib 对齐） |
| Gradle | 9.6.1（已带 wrapper） |

本机用 mise 管理 JDK：

```bash
mise install java@temurin-25.0.4+101.0.LTS
mise use java@temurin-25.0.4+101.0.LTS
```

## 构建与运行

```bash
./gradlew test                              # 单元测试 + 面板构造测试
./gradlew verifyPluginProjectConfiguration  # 插件配置校验
./gradlew runIde                            # 启动沙箱 IDE 调试
./gradlew buildPlugin                       # 产出 build/distributions/maven-search-*.zip
./gradlew verifyPlugin                      # Plugin Verifier 兼容性检查
```

安装到本机 IDEA：`Settings → Plugins → ⚙ → Install Plugin from Disk...`，选择
`build/distributions/` 下的 zip，重启 IDE。工具窗口在右侧边栏的 **Maven Coordinates**。

## 使用

1. 打开右侧 **Maven Search** 工具窗口；
2. 输入关键词（`guava`）、坐标（`com.google.guava:guava`）或完整类名（`com.google.gson.Gson`）；
3. 选中左侧 artifact，下方会列出 `maven-metadata.xml` 里的全部版本；
4. 在版本上按 `Enter` / 双击 / `Ctrl+C`，或点「复制」，把片段写入剪贴板。

| 操作 | 快捷键 |
|---|---|
| 立即搜索（跳过防抖） | `Enter` |
| 清空搜索框 | `Esc` |
| 复制选中版本 | `Enter` / 双击 / `Ctrl+C` |

底部下拉框可切换片段格式：Maven XML / Gradle Groovy / Gradle Kotlin DSL / 仅坐标。

## 数据源

数据源下拉框提供两个选项，区别只在**关键词搜索**先用哪个端点；版本列表和类名反查两个源完全一致。

| 能力 | 端点 | 说明 |
|---|---|---|
| 关键词搜索（默认） | `search.maven.org/solrsearch/select` | 排序明显更好：搜 `guava` 第一条就是 `com.google.guava:guava` |
| 关键词搜索（Fast） | `central.sonatype.com/solrsearch/select` | 更快更稳，但把 `guava` 排到第 9 位 |
| 全部版本 | `repo1.maven.org/.../maven-metadata.xml` | 一次请求拿全量版本，带 `<release>`，最权威 |
| 类名反查 | `search.maven.org/solrsearch/select` + `fc:` | 唯一可用的类名索引 |

两个端点会**互相兜底**：主端点超时或失败时自动切到另一个。实测 legacy 端点约 1/5 的请求会超时，
新端点稳定但排序差，所以默认「legacy 优先、新端点兜底」。

### 为什么不用搜索接口里的 `latestVersion`

实测 `search.maven.org` 的 `latestVersion` 会滞后数月（guava 显示 `33.4.8-jre`，实际已是
`33.7.2-jre`）。所以列表里不展示这个字段，版本信息一律以选中后加载的 `maven-metadata.xml` 为准。

## 类名反查的准确性

Maven Central 的 `fc:` 类名索引对大型 artifact 覆盖很差：搜 `com.google.common.collect.Lists`，
前 100 条全是 shade/relocate 过的 `repackaged.*` 副本，真正的 guava 根本不在里面。

插件的处理方式：

1. 请求带 `hl=true&hl.fl=fch`，拿到索引**实际匹配到的类名原文**；
2. 去掉 `<em>` 后**完全等于**查询串才算精确匹配（`EXACT`）；
3. 带 `repackaged.` 等前缀的一律降级为近似结果（`RELOCATED`），**默认隐藏**；
4. 空结果会区分「没有精确匹配，另有 N 个近似结果」和「完全没有命中」，不会只显示「未找到」。

结果行会显示「匹配: com.google.gson.Gson」，让你能看到插件凭什么给出这个坐标。
异步校验通过的条目还会显示 **✓**：插件会从 `repo1` 用 HTTP Range 读取 jar 末尾
64KB 定位中央目录，再按需取回中央目录本身，**直接从 jar 内容确认这个 `.class` 存在**。
这条路径不依赖任何搜索索引，通常只需下载 60-270KB。

### 类名索引不可用时的降级

公开的类名索引只有 `search.maven.org` 的 `fc:` 一个，全量索引文件约 3.3GB，
不适合放进插件。因此当该索引下线、被拦截或持续超时时，插件会：

1. 识别为「类名索引不可用」，不弹通用网络错误；
2. 改用稳定端点（`central.sonatype.com`）按**简单类名**找候选；
3. 对每个候选读取 jar 中央目录做验证，**只返回 jar 里确实存在该类的 artifact**；
4. 在状态栏明确标注这是降级结果。

召回率取决于简单类名是否有辨识度：`com.google.gson.Gson` 能找到（真正的 gson 在前 20
个候选里），`com.google.common.collect.Lists` 则可能返回 0 条。但降级路径**不会给出未经验证
的坐标**，且关键词搜索与版本列表完全不受影响。

> 这是数据源本身的召回缺陷，不是实现问题。要彻底消除对 `fc:` 索引的依赖，只能自行
> 运营索引服务或下载并维护 3.3GB 的 Maven Central 全量索引。

## 代码结构

```
src/main/kotlin/dev/felix/mavensearch/
├── model/        坐标、版本、类名匹配等数据模型
├── net/          HttpFetcher 接口 + 走 IDE 代理的 HttpRequests 实现
├── source/       数据源抽象与 Maven Central 实现
│   └── central/  URL 构造、Solr JSON 解析、maven-metadata.xml 解析（XXE 加固）
├── service/      application 级服务、TTL 缓存
├── settings/     PersistentStateComponent（maven-search.xml）
├── search/       输入意图分类（g:a / 关键词 / FQCN）
├── snippet/      Maven XML / Gradle 片段生成 + 坐标白名单校验
├── ui/           工具窗口、主面板、列表渲染器
└── i18n/         DynamicBundle（英文 / 中文）
```

设计要点：

- **防串台**：防抖 + Job 取消 + **世代号比对**。取消打断不了阻塞的 socket 读，只有回 EDT
  后比对世代号才能保证旧请求不覆盖新结果。
- **可测试**：HTTP 全部走 `HttpFetcher` 接口注入，单元测试用真实响应 fixture，完全离线。
- **不信任远端数据**：坐标先过白名单再拼进片段；`maven-metadata.xml` 解析关闭外部实体防 XXE。

## 测试

```bash
./gradlew test
```

41 个测试：Solr JSON 解析、metadata 解析（含 XXE 拒绝）、URL/Lucene 转义、输入分类、
片段生成、数据源回退、缓存、设置序列化往返、jar 中央目录解析与 HTTP Range 校验，
以及在真实平台环境里构造面板。
