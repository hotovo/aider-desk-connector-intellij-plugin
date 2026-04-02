# IDEA 2026.1 兼容性升级说明

## 当前确认环境
- IntelliJ IDEA 2026.1
- Build: `IU-261.22158.277`
- 对应主构建线：`261`

## 实际已完成的调整

本次兼容性升级并不是完全按最初方案逐项落地，而是基于当前项目实际情况做了必要调整。

### 1. `build.gradle.kts`
已实际采用的构建配置包括：

- `org.jetbrains.kotlin.jvm` 升级为 `2.3.20`
- `org.jetbrains.intellij.platform` 升级为 `2.10.5`
- IntelliJ 平台目标版本使用 `intellijIdea("2026.1")`
- 保留 `bundledPlugin("Git4Idea")`
- Java / Kotlin 目标版本保持为 `21`
- 新增：
  - `slf4j` 版本为 `2.0.17`
  - `jackson-module-kotlin` 依赖坐标调整为 `tools.jackson.module:jackson-module-kotlin:3.1.1`
- 保留并使用：
  - `buildSearchableOptions { enabled = false }`

### 2. `plugin.xml`
已实际完成的兼容性声明包括：

- 增加 `<idea-version since-build="261"/>`
- 继续使用 `postStartupActivity`
- 保留现有状态栏组件与动作注册配置

## 未按原方案严格处理的部分

以下内容需要特别说明：

1. **并未删除旧的 `patchPluginXml` 逻辑后再单独新增显式配置**
   - 当前构建仍会在任务执行期间对 `plugin.xml` 做平台兼容性补丁处理
   - 构建日志中可见：
     - `attribute 'since-build=[261]' of 'idea-version' tag will be set to '261.22158'`

2. **并未对 Kotlin 代码做兼容性改造**
   - `AiderDeskConnectorPluginStartupActivity.kt` 保持不变
   - `AiderDeskStatusBarWidget.kt` 保持不变
   - `AiderDeskStatusBarWidgetFactory.kt` 保持不变
   - 当前兼容性升级主要集中在构建配置和插件声明层面

3. **额外加入了构建规避项**
   - 为避免 `buildSearchableOptions` 任务在当前环境下失败，已显式禁用该任务：
     - `buildSearchableOptions { enabled = false }`
   - 触发原因不是业务代码问题，而是构建阶段启动 IDE 子进程时出现 Java Agent / classpath 相关异常
   - 典型报错表现为：
     - `ClassNotFoundException: kotlinx.coroutines.debug.internal.AgentPremain`

## 关于 `buildSearchableOptions { enabled = false }`

该配置的含义是：

- 禁用 IntelliJ 插件构建过程中的 `buildSearchableOptions` 任务
- 不再为插件生成 Settings 搜索相关的 searchable options 数据

对当前项目的影响：

- 不影响插件编译、打包、安装和主要运行功能
- 当前项目未体现出明显的 Settings / Preferences 配置页，因此该任务不是必需项
- 该配置本质上是一个稳定性规避措施，而不是兼容性核心改造项

## 本次升级的结论

本次 IDEA 2026.1 兼容性升级，**实际完成的是“构建配置 + plugin.xml 声明 + 构建问题规避”**，而不是完全照原始方案逐条执行。

换句话说：

- **核心目标已达成**：项目可面向 IntelliJ IDEA 2026.1 构建
- **实现路径有调整**：以当前项目实际可用、可构建为准
- **额外增加了构建兜底措施**：禁用 `buildSearchableOptions`

## 建议验证项

建议继续使用以下方式验证：

1. 执行 `buildPlugin`
2. 执行 `runIde`
3. 在 IntelliJ IDEA 2026.1 中手动安装插件验证
4. 重点验证：
   - 启动后自动连接是否正常
   - 状态栏组件是否显示正常
   - 项目右键菜单与编辑器右键菜单动作是否可用
   - Git4Idea 相关依赖是否正常加载
