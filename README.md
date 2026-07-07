# 火崽崽助手（HZZS）

<p align="center">
  <b>一个使用 Kotlin 与 Android 原生技术开发的独立工具应用。</b><br>
  当前处于早期开发阶段，重点是建立清晰、稳定、可维护、可长期迭代的本地工具体验。
</p>

<p align="center">
  <a href="#当前状态">当前状态</a> ·
  <a href="#开发路线图">开发路线图</a> ·
  <a href="#本地构建">本地构建</a> ·
  <a href="#数据与隐私">数据与隐私</a> ·
  <a href="#开源许可证">开源许可证</a>
</p>

> [!IMPORTANT]
> 本仓库目前仍处于早期开发阶段。README 中标注为“计划中”或“规划中”的页面、能力与流程，不代表当前版本已经实现或可用。

## 项目简介

火崽崽助手（HZZS）是一个独立开发的 Android 应用项目。

项目将使用 Kotlin 与 Android 原生技术，逐步构建一个以**状态查看、权限引导、本地记录、数据分析、参数校准、异常诊断与界面体验**为核心的本地工具应用。

开发过程中会优先保证：

- **信息清晰**：关键状态、权限与问题提示应当容易理解。
- **本地优先**：记录、配置与诊断信息优先保存于设备本地。
- **反馈明确**：出现异常时尽量说明发生了什么、可能原因与下一步处理方向。
- **结构可维护**：页面、数据、服务与工具代码保持清晰边界，方便长期迭代。
- **稳定优先**：在扩展功能前优先考虑兼容性、可靠性与可回退性。
- **界面统一**：后续界面计划采用深色 Material Design 3 风格，减少无意义信息堆叠，突出当前最重要的操作与反馈。

本项目为独立开发项目，不隶属于、不代表、也未获得任何第三方平台、品牌、游戏或服务的官方授权。

## 当前状态

当前版本：

```text
0.1.0
```

当前版本的定位是建立正式的 Android 开发基础，还不是功能完整的稳定版本。

### 已完成

- [x] 创建 Android 原生项目基础结构
- [x] 初始化 Git 仓库并建立基础版本记录
- [x] 确定应用名称为「火崽崽助手」
- [x] 确定项目代号为 `HZZS`
- [x] 配置 Gradle 项目名为 `hzzs`
- [x] 配置 Android Package、Namespace 与 Application ID 为 `top.azek431.hzzs`
- [x] 配置应用主题与基础资源
- [x] 配置本地 Release 签名并完成 APK 签名验证
- [x] 成功构建首个已签名 Release APK
- [x] 保留基础 XML 页面模板与 Material Components 依赖
- [x] 建立后续开发所需的基础构建环境
- [x] 添加 MIT 开源许可证

### 尚未完成

- [ ] 正式首页与完整页面导航
- [ ] 运行状态与权限检查界面
- [ ] 本地记录、数据统计与趋势分析
- [ ] 参数校准与异常诊断能力
- [ ] 完整的用户使用流程与帮助文档
- [ ] 自动化构建、发布与版本更新流程
- [ ] 首个稳定可测试版本

## 项目目标

火崽崽助手希望提供一个状态明确、信息直观、方便长期维护的 Android 使用体验。

预期使用流程：

```text
打开应用
→ 查看当前状态
→ 检查权限与设备环境
→ 使用相关功能
→ 查看本地记录与数据概览
→ 根据提示进行校准或异常诊断
```

后续所有功能设计都会尽量满足以下要求：

| 原则 | 说明 |
| --- | --- |
| 状态清晰 | 用户应能快速知道当前是否可用、正在进行什么、下一步需要做什么。 |
| 权限透明 | 每项系统权限应说明用途、当前状态与缺失时的处理入口。 |
| 本地优先 | 默认不主动上传运行记录、截图、日志或设备信息。 |
| 错误可解释 | 异常信息不只显示失败结果，也尽量提供原因分类与处理建议。 |
| 数据可管理 | 用户应能查看、导出、清理与理解本地数据。 |
| 扩展可维护 | 不把所有逻辑堆在单一页面或单一 Activity 中，为后续功能保留清晰边界。 |

## 计划中的页面结构

以下内容属于开发规划，不代表当前版本已经全部实现。

```text
火崽崽助手
│
├─ 首页
│  ├─ 当前状态概览
│  ├─ 权限与设备健康状态
│  ├─ 今日数据摘要
│  ├─ 快速操作入口
│  └─ 最近提示与异常信息
│
├─ 运行
│  ├─ 当前任务状态
│  ├─ 运行控制入口
│  ├─ 实时状态信息
│  └─ 最近运行结果
│
├─ 分析
│  ├─ 今日统计
│  ├─ 历史记录
│  ├─ 完成率与耗时趋势
│  ├─ 常见异常分类
│  └─ 本地数据概览
│
└─ 我的
   ├─ 权限管理
   ├─ 校准中心
   ├─ 异常诊断
   ├─ 本地数据管理
   ├─ 关于应用
   └─ 更新与反馈入口
```

## 开发路线图

路线图用于说明开发优先级，并会随着实际需求、稳定性和设备适配情况调整。

### Milestone 0：项目基础与发布身份

**目标：建立可持续开发、可安全发布的 Android 工程基础。**

- [x] 创建 Android 原生项目
- [x] 完成项目名称、应用名称、包名与主题命名
- [x] 配置基础构建环境
- [x] 创建并验证本地 Release 签名
- [x] 构建首个已签名 Release APK
- [x] 添加 README 与 MIT 许可证
- [ ] 完善 `.gitignore` 的签名与本地配置保护规则
- [ ] 建立 GitHub Issue 模板与基础协作规范

### Milestone 1：界面基础与导航框架

**目标：从默认模板过渡到完整、统一的应用壳层。**

- [ ] 替换默认 XML 示例页面
- [ ] 建立火崽崽助手首页
- [ ] 建立统一的深色界面基础
- [ ] 建立首页、运行、分析、我的页面框架
- [ ] 建立顶部栏、底部导航与页面跳转结构
- [ ] 实现空状态、加载状态、错误状态与权限缺失状态
- [ ] 完善基础交互反馈与无障碍标签

### Milestone 2：状态、权限与设备检查

**目标：让用户可以清楚地了解当前设备与应用环境是否处于可用状态。**

- [ ] 建立权限检查页面
- [ ] 展示关键权限与服务状态
- [ ] 增加权限缺失提示与重新检查入口
- [ ] 建立设备环境健康检查
- [ ] 增加常见问题说明
- [ ] 优化不同 Android 版本与设备上的适配体验
- [ ] 建立统一的状态模型与错误原因分类

### Milestone 3：本地记录与数据分析

**目标：建立可理解、可管理的本地数据能力。**

- [ ] 建立本地记录数据模型
- [ ] 展示今日次数、完成率与累计时长
- [ ] 增加历史记录列表
- [ ] 增加数据概览与趋势展示
- [ ] 建立异常原因分类
- [ ] 增加本地日志查看能力
- [ ] 支持本地数据导出、导入与清理
- [ ] 默认关闭任何未明确说明的数据上传行为

### Milestone 4：校准与异常诊断

**目标：让常见问题能够被定位、解释并获得可执行的处理提示。**

- [ ] 建立校准中心
- [ ] 增加参数保存、恢复与重置能力
- [ ] 建立异常诊断页面
- [ ] 提供问题定位提示与恢复建议
- [ ] 增加设备适配信息
- [ ] 优化异常说明、错误记录与反馈流程

### Milestone 5：质量、发布与维护

**目标：形成可重复的测试、打包、发布与问题反馈流程。**

- [ ] 完善应用图标、启动页与关于页面
- [ ] 发布首个可测试版本
- [ ] 建立 GitHub Releases 发布流程
- [ ] 编写版本更新日志与升级说明
- [ ] 编写用户使用说明与常见问题
- [ ] 建立 Debug / Release 构建检查清单
- [ ] 建立基本的兼容性测试流程
- [ ] 根据反馈持续优化稳定性、性能与界面体验

## 项目信息

| 项目 | 内容 |
| --- | --- |
| 应用名称 | 火崽崽助手 |
| 项目代号 | HZZS |
| Gradle 项目名 | `hzzs` |
| Android Package | `top.azek431.hzzs` |
| Android Namespace | `top.azek431.hzzs` |
| 当前版本 | `0.1.0` |
| 最低 Android 版本 | API 24（Android 7.0） |
| 编译 SDK | API 37 |
| 目标 SDK | API 37 |
| 开发语言 | Kotlin |
| 当前 UI 基础 | Android Views + Material Components |
| 后续 UI 方向 | 深色 Material Design 3 风格 |
| 构建工具 | Gradle Wrapper |
| 推荐 JDK | JDK 17 |
| 正式签名格式 | PKCS12（本地私有保存，不提交） |

## 当前项目结构

当前项目仍处于早期阶段，目录结构会随着功能增加逐步拆分和完善。

```text
hzzs/
├─ app/
│  ├─ src/
│  │  ├─ main/
│  │  │  ├─ java/
│  │  │  │  └─ top/
│  │  │  │     └─ azek431/
│  │  │  │        └─ hzzs/
│  │  │  │           └─ MainActivity.kt
│  │  │  ├─ res/
│  │  │  └─ AndroidManifest.xml
│  │  ├─ build.gradle.kts
│  │  └─ proguard-rules.pro
├─ gradle/
├─ build.gradle.kts
├─ gradle.properties
├─ settings.gradle.kts
├─ gradlew
├─ gradlew.bat
├─ LICENSE
└─ README.md
```

后续预计会逐步增加类似结构：

```text
app/src/main/java/top/azek431/hzzs/
├─ ui/
│  ├─ home/
│  ├─ run/
│  ├─ analysis/
│  ├─ profile/
│  ├─ calibration/
│  └─ diagnostics/
├─ data/
├─ model/
├─ service/
└─ util/
```

以上目录仅为后续规划，实际结构会根据项目规模和功能需求调整。

## 开发环境

推荐使用以下环境进行开发：

```text
操作系统：
Windows 10 / Windows 11

编辑器：
Visual Studio Code

Java：
JDK 17

Android SDK Platform：
API 37

Android SDK Build-Tools：
37.0.0

构建工具：
Gradle Wrapper
```

项目使用 Gradle Wrapper，通常不需要额外全局安装 Gradle。

## 本地构建

在项目根目录打开 PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 默认输出位置：

```text
appuild\outputspk\debugpp-debug.apk
```

Debug APK 使用 Android 自动生成的调试签名，仅适合本地开发和测试。

## Release 构建与签名

正式 Release APK 使用开发者本机保存的私有发布签名。

为保护私钥、证书与密码，以下内容不会提交到 Git 仓库：

```text
发布签名文件
签名密码
私钥密码
本地密钥库路径
个人环境变量
本地签名配置
```

请勿将以下类型文件提交到 GitHub：

```text
*.jks
*.keystore
*.p12
*.pfx
keystore.properties
signing.properties
包含密码的 local.properties
```

Release 包应在本地安全环境中构建，并在发布前进行签名核验。

仓库不会保存发布私钥、签名密码或其他敏感签名信息。

## 数据与隐私

项目后续会优先采用本地数据管理策略。

计划遵循以下原则：

- 默认优先在设备本地保存记录与配置。
- 不主动上传运行记录、截图、日志或设备信息。
- 涉及系统权限时明确说明用途。
- 用户应能够查看、导出或清理本地数据。
- 不在未说明的情况下收集个人信息。
- 不在仓库中保存用户隐私数据或敏感配置。

## 使用与合规说明

本项目是独立开发的工具应用。

请在遵守当地法律法规、设备系统规则、服务条款与平台规则的前提下使用。

本项目不会以任何形式宣称自己是第三方平台、品牌、游戏或服务的官方产品，也不应被理解为官方合作、官方授权或官方推荐工具。

请勿将本项目用于：

- 违反法律法规的行为
- 违反平台规则或服务条款的行为
- 损害他人权益的行为
- 影响他人正常使用服务的行为
- 传播恶意软件、隐私数据或未经授权的内容

## 贡献与反馈

项目仍在早期开发阶段，欢迎通过 GitHub Issues 提交：

- 功能建议
- UI 与交互建议
- 构建问题
- 设备兼容问题
- 崩溃信息
- 使用体验反馈
- 文档错误或改进建议

提交 Issue 时，建议尽量附上：

```text
设备型号
Android 版本
应用版本
问题描述
复现步骤
相关截图
错误日志
```

请不要在 Issue、Discussion、Pull Request 或截图中上传：

```text
签名文件
密钥库密码
私钥密码
个人账号密码
身份证明
手机号
真实住址
其他隐私信息
```

提交代码前，请确保：

- 不包含任何密码、私钥、签名文件或个人配置。
- 不复制来源不明或许可证不兼容的代码与资源。
- 不把规划中的功能描述成已经实现。
- 提交说明能够准确反映改动目的。

## Star History

<a href="https://www.star-history.com/?repos=Azek431%2Fhzzs&type=timeline&logscale=&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Azek431/hzzs&type=timeline&theme=dark&logscale&legend=top-left&sealed_token=5nGYwu6dyFZCBkjQpE4_BONnzaLSsYtPWFhzRt8hTVk7wquqIFambdz031_AYxA8S-cAZSGChh7QXUpVenbo_bG-VJlg0d4nmQ56p6FnI_N4pIbOL8X-20nvQNActWBIUsQGhTm4hI-mDS0YRnBn5d0_o5ICvU3Q6Qp717Eau6y_c1l3X-hdJgRUhsk0" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Azek431/hzzs&type=timeline&logscale&legend=top-left&sealed_token=5nGYwu6dyFZCBkjQpE4_BONnzaLSsYtPWFhzRt8hTVk7wquqIFambdz031_AYxA8S-cAZSGChh7QXUpVenbo_bG-VJlg0d4nmQ56p6FnI_N4pIbOL8X-20nvQNActWBIUsQGhTm4hI-mDS0YRnBn5d0_o5ICvU3Q6Qp717Eau6y_c1l3X-hdJgRUhsk0" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Azek431/hzzs&type=timeline&logscale&legend=top-left&sealed_token=5nGYwu6dyFZCBkjQpE4_BONnzaLSsYtPWFhzRt8hTVk7wquqIFambdz031_AYxA8S-cAZSGChh7QXUpVenbo_bG-VJlg0d4nmQ56p6FnI_N4pIbOL8X-20nvQNActWBIUsQGhTm4hI-mDS0YRnBn5d0_o5ICvU3Q6Qp717Eau6y_c1l3X-hdJgRUhsk0" />
 </picture>
</a>

## 开源许可证

本项目采用 [MIT License](./LICENSE) 开源。

MIT 协议允许他人使用、复制、修改、合并、发布、分发、再授权和销售本项目的软件副本；使用或分发时需保留原始版权声明与许可证文本。第三方依赖、素材或资源仍可能适用其各自的许可证，使用前应自行核对。

---

Built with ❤️ by [Azek431](https://github.com/Azek431)

<!-- HZZS-COMMUNITY-START -->
## 交流与动态

- HZZS 项目 QQ 交流群（测试、反馈与问题讨论）：[点击加入群聊【火崽崽助手 交流群】](https://qm.qq.com/q/5T5fjwRgVq)
- Azek431 主频道（同步 HZZS 与更多项目动态）：[打开 @AzekMain](https://t.me/AzekMain)
<!-- HZZS-COMMUNITY-END -->
