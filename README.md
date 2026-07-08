# APKAgent

<p align="center">
  <b>项目化 APK 逆向工作台</b><br>
  面向中文用户的原生 Android APK 分析、项目管理、AI 协作与补丁导出工具
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-green"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-blue"/>
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material3-purple"/>
  <img alt="Version" src="https://img.shields.io/badge/version-4.0.0-orange"/>
</p>

---

## 简介

APKAgent 不是单纯的“APK 聊天工具”，而是一个**项目化 APK 逆向工作台**：

- 导入 APK 或已安装应用后，自动创建独立项目目录
- 自动生成预分析摘要，避免 AI 在空上下文下瞎猜
- 通过 Agent + Tool Calling 执行 APK 分析、反编译、patch、重打包、签名
- 提供项目历史、项目文件、脚本运行环境、导出 APK 闭环

核心目标：**让 APK 逆向从一次性操作，升级为可持续管理的项目流程。**

---

## 当前产品形态

### 1. 项目化工作区
每个导入对象都会创建独立项目目录，典型结构包括：

- `source/`：原始 APK
- `analysis/`：预分析结果
- `chat/`：项目内 AI 历史
- `exports/`：导出产物
- `patches/`：补丁相关文件
- `scripts/`：项目脚本

### 2. 预分析优先
导入后会生成项目摘要，供 AI 对话注入上下文，包括：

- 包名 / 版本 / SDK
- 权限与组件摘要
- DEX / Native so / assets 概览
- 证书摘要
- 基础风险提示

### 3. AI Agent + Tool Calling
支持 OpenAI 兼容接口，AI 可以调用内置工具进行：

- Manifest / DEX / 签名读取
- 反编译 / 回编译 / patch
- 文件读写 / 搜索 / 脚本执行
- 导出重打包并签名

### 4. 运行时环境
项目支持：

- Python 运行环境
- Node.js 运行环境
- Shizuku 提权执行
- 内置终端

当前已引入 `RuntimeManager` 作为统一运行时入口，逐步收敛分散的检测/执行逻辑。

### 5. 真实敏感操作确认
敏感工具调用不再默认自动放行。现在会进入确认队列，由 UI 展示：

- 工具名
- 风险级别
- 参数摘要

用户允许后才继续执行。

---

## 核心能力

### APK 项目导入
- 导入本地 APK
- 导入已安装应用
- 为每个目标创建独立项目
- 自动预分析并生成项目摘要

### 静态分析
- APK 基本信息
- AndroidManifest 解析
- 签名信息读取
- DEX / smali 分析
- 文件/字符串搜索
- 基础风险扫描

### 修改与导出
- 自动 patch
- DEX / smali 修改
- APK 解包 / 重打包
- APK 重签名
- 导出 patched APK

### 项目协作
- 项目内 AI 历史
- 项目文件浏览与编辑
- 项目化工作区
- 可扩展深分析入口

---

## 权限模型

APKAgent 是工具型 App，不追求沙盒极简，而追求“能干活”。因此会使用较强权限能力：

- `MANAGE_EXTERNAL_STORAGE`：访问项目与导出目录
- `QUERY_ALL_PACKAGES`：导入已安装应用
- `REQUEST_INSTALL_PACKAGES`：安装导出 APK
- `FOREGROUND_SERVICE` / `WAKE_LOCK`：长任务保活
- `Shizuku`：执行高权限 shell / runtime 操作

> 这类权限适合逆向工具，但不适合常规上架型产品。

---

## 安全与确认模型

### 工具风险等级
- `SAFE`：只读操作
- `CAUTION`：可能读取敏感内容或产生中等副作用
- `DANGER`：patch、写入、签名、安装、重打包等高副作用操作

### 当前访问策略
当前 `Sandbox` 更准确地说是“路径解析与访问策略占位层”，主要职责是：

- 相对路径基于工作区解析
- 预留未来接入真实文件访问策略层的 seam

它**不是传统严格沙箱**。

---

## 架构概览

主要模块：

- `agent/`：AgentLoop、OpenAIClient、ToolRegistry、确认机制
- `apktools/`：APK 分析、smali、签名、patch、脚本工具
- `project/`：项目模型、项目存储、快速预分析
- `runtime/`：运行时状态与执行入口（新）
- `chat/`：聊天历史管理（新）
- `export/`：APK 导出管理（新）
- `ui/`：Compose 界面
- `shizuku/`：提权执行

相关文档：
- `docs/architecture/apkagent-refactor-overview.md`
- `docs/architecture/runtime-and-confirmation.md`
- `docs/plans/2026-07-08-apkagent-perfect-refactor.md`

---

## 构建

```bash
cd APKAgent
./gradlew test
./gradlew assembleDebug
```

产物通常位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 已知限制

- 深分析能力目前仍以“快速摘要 + 工具逐步展开”为主，重型索引还在扩展中
- Android ROM 差异会影响存储权限、Shizuku、外部 runtime 的表现
- 这是面向研究与改造流程的工具，不是面向商店审核的轻权限 App

---

## 适用场景

- Android APK 静态分析
- 逆向研究与补丁验证
- 项目化 APK 工作流管理
- 借助 AI 进行中文化逆向协作

---

## License

MIT License
