# APKAgent — AI 驱动的 Android APK 逆向分析工具

[![Build APK](https://github.com/Bailan766/APKAgent/actions/workflows/build.yml/badge.svg)](https://github.com/Bailan766/APKAgent/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Bailan766/APKAgent)](https://github.com/Bailan766/APKAgent/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **一句话介绍：** 在手机上用自然语言对话，让 AI 自动完成 APK 反编译、Smali 修改、资源替换、重打包、签名、安装的全流程逆向工程工具。

---

## 目录

- [功能概览](#功能概览)
- [技术架构](#技术架构)
- [核心模块详解](#核心模块详解)
  - [Agent 核心](#1-agent-核心agent)
  - [APK 工具集](#2-apk-工具集apktools)
  - [高级工具集](#3-高级工具集apktoolsadvanced)
  - [脚本运行器](#4-脚本运行器scripttools)
  - [Shizuku 集成](#5-shizuku-集成shizuku)
  - [网络层](#6-网络层network)
  - [UI 层](#7-ui-层ui)
  - [服务层](#8-服务层service)
- [全部 53 个 AI 工具清单](#全部-53-个-ai-工具清单)
- [构建与 CI/CD](#构建与-cicd)
- [环境要求](#环境要求)
- [项目目录结构](#项目目录结构)

---

## 功能概览

| 能力 | 说明 |
|------|------|
| 🤖 AI 对话驱动 | 自然语言下达指令，AI 自动选择并调用工具 |
| 📦 APK 解包/重打包 | 基于 apktool 的反编译和重新编译 |
| 🔧 Smali 编辑 | 搜索、替换、注入 Smali 字节码 |
| 📝 资源修改 | 修改 strings.xml、布局、图片等资源 |
| 🔐 签名/对齐 | APK 签名（V1/V2/V3/V4）和 zipalign 对齐 |
| 📋 证书/权限分析 | 查看签名证书详情、权限列表 |
| 🐍 Python/Node 脚本 | 在设备上执行 Python 和 Node.js 脚本 |
| 📱 直接安装 | 通过 Shizuku 直接安装 APK 到设备 |
| 🎨 11 套主题 | 暗黑、亮色、紫色、蓝绿、橙色、粉色等 |
| 🔄 10 个 AI Provider | OpenAI、DeepSeek、通义、Kimi、GLM、文心等 |

---

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ChatScreen│ │EditorScr │ │TerminalScr│ │SettingsScreen │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └───────┬───────┘  │
│       │             │            │               │          │
│  ┌────▼─────────────▼────────────▼───────────────▼───────┐  │
│  │                   ChatViewModel                        │  │
│  │              (StateFlow + MVVM)                        │  │
│  └───────────────────────┬───────────────────────────────┘  │
├──────────────────────────┼──────────────────────────────────┤
│                    Agent Core                                │
│  ┌───────────┐  ┌────────▼────────┐  ┌───────────────────┐  │
│  │ OpenAI    │  │   AgentLoop     │  │   ToolRegistry    │  │
│  │ Client    │←→│  (状态机驱动)    │←→│  (工具注册/分发)   │  │
│  │ (SSE流式)  │  │                 │  │                   │  │
│  └───────────┘  └─────────────────┘  └────────┬──────────┘  │
├───────────────────────────────────────────────┼──────────────┤
│                 Tool Layer (53 tools)          │              │
│  ┌────────────┐ ┌──────────┐ ┌────────────────▼───────────┐ │
│  │ ApkTools   │ │ScriptTools│ │   Advanced Tools           │ │
│  │ (30+ tools)│ │(py/node/sh)│ │(zipalign/cert/sign/...)  │ │
│  └─────┬──────┘ └─────┬────┘ └────────────┬───────────────┘ │
├────────┼──────────────┼───────────────────┼─────────────────┤
│        │    Execution Layer                │                  │
│  ┌─────▼──────────────▼───────────────────▼───────────────┐ │
│  │              ShizukuManager (uid 2000)                   │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │ │
│  │  │File Ops  │ │PythonRun │ │NodeRunner│ │Shell Exec │  │ │
│  │  │(读写/复制) │ │(/tmp部署) │ │(/tmp部署) │ │(sh -c)   │  │ │
│  │  └──────────┘ └──────────┘ └──────────┘ └───────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│                    Android System                             │
│  /data/user/0/com.apkagent/   (App 私有目录)                  │
│  /data/local/tmp/              (Shizuku 可执行目录)            │
│  /sdcard/Download/APKAgent/    (工作区)                       │
└──────────────────────────────────────────────────────────────┘
```

**技术栈：**

| 组件 | 技术选型 |
|------|----------|
| UI 框架 | Jetpack Compose + Material3 |
| 架构模式 | MVVM (ViewModel + StateFlow) |
| AI 通信 | OkHttp + SSE (Server-Sent Events) 流式传输 |
| 序列化 | Gson |
| 提权方案 | Shizuku (ADB shell uid 2000) |
| 存储 | EncryptedSharedPreferences (API Key 加密) |
| 最低版本 | Android 11 (API 30) |
| 构建工具 | Gradle Kotlin DSL + AGP |
| CI/CD | GitHub Actions |

---

## 核心模块详解

### 1. Agent 核心（agent/）

#### AgentLoop.kt — AI 对话循环引擎

**核心职责：** 管理 AI 对话的完整生命周期，采用**状态机**架构。

```
状态流转：
IDLE → THINKING → STREAMING → TOOL_CALL → TOOL_EXEC → STREAMING → ... → COMPLETE → IDLE
```

**关键设计：**

- **状态机驱动**：使用 `StateFlow<AgentState>` 发射状态变化，UI 通过 Collect 响应
- **流式处理**：AI 响应通过 SSE 逐 token 接收，实时更新 UI
- **工具循环**：AI 返回 `tool_calls` → 执行工具 → 将结果追加到对话 → 再次调用 AI
- **错误恢复**：API 调用失败自动重试（最多 3 次），工具执行异常捕获后反馈给 AI
- **上下文管理**：对话历史保留在 `messages: MutableList<Message>` 中，支持多轮对话

**核心流程伪代码：**
```kotlin
fun runAgent(userMessage: String) {
    messages.add(UserMessage(userMessage))
    state = AgentState.THINKING
    
    while (state != AgentState.COMPLETE) {
        val response = openAIClient.chatCompletion(messages, tools)
        
        if (response.hasToolCalls()) {
            state = AgentState.TOOL_EXEC
            for (call in response.toolCalls) {
                val result = toolRegistry.execute(call.name, call.arguments)
                messages.add(ToolResult(call.id, result))
            }
            // 继续循环，让 AI 处理工具结果
        } else {
            messages.add(AssistantMessage(response.content))
            state = AgentState.COMPLETE
        }
    }
}
```

#### OpenAIClient.kt — AI API 通信客户端

**核心职责：** 与 OpenAI 兼容 API 通信，支持 SSE 流式传输。

**关键设计：**

- **SSE 流式**：使用 OkHttp 的 `EventSource` 实现 Server-Sent Events，逐 token 显示 AI 响应
- **多 Provider 支持**：10 个预设 Provider + 自定义端点
- **请求格式**：标准 OpenAI Chat Completions API (`/v1/chat/completions`)
- **Tool Calling**：将工具定义以 JSON Schema 格式传递给 AI，解析返回的 `tool_calls`
- **错误处理**：区分可重试错误（429/5xx）和不可重试错误（401/403）

**支持的 Provider：**

| Provider | Base URL | 模型示例 |
|----------|----------|----------|
| OpenAI | api.openai.com | gpt-4o, gpt-4-turbo |
| DeepSeek | api.deepseek.com | deepseek-chat, deepseek-reasoner |
| 通义千问 | dashscope.aliyuncs.com | qwen-max, qwen-plus |
| Kimi | api.moonshot.cn | moonshot-v1-128k |
| 智谱 GLM | open.bigmodel.cn | glm-4, glm-4v |
| 零一万物 | api.lingyiwanwu.com | yi-large |
| 百度文心 | aip.baidubce.com | ernie-4.0-8k |
| 讯飞星火 | spark-api-open.xf-yun.com | spark-max |
| MiniMax | api.minimax.chat | abab6.5-chat |
| 自定义 | 用户配置 | 任意 OpenAI 兼容 |

#### ToolRegistry.kt — 工具注册与分发中心

**核心职责：** 管理所有 AI 可调用工具的注册、查找、执行。

**设计模式：** 注册表模式 (Registry Pattern)

```kotlin
// 注册工具
registry.register(
    name = "apk_info",
    description = "获取 APK 基本信息",
    parameters = mapOf(
        "path" to Parameter("string", "APK 文件路径", required = true)
    ),
    handler = { args -> getApkInfo(args["path"]) }
)

// AI 调用时自动分发
val result = registry.execute("apk_info", """{"path":"/sdcard/app.apk"}""")
```

---

### 2. APK 工具集（apktools/）

#### ApkTools.kt — 核心 APK 操作工具（~1720 行）

**注册了 30+ 个工具**，覆盖 APK 逆向分析的各个方面。

**核心能力：**

| 类别 | 工具 | 说明 |
|------|------|------|
| 解包 | `apk_decode` | apktool 反编译 APK 到 smali + 资源 |
| 打包 | `apk_build` | apktool 重新编译为 APK |
| 安装 | `apk_install` | 通过 Shizuku 安装 APK |
| 签名 | `apk_sign` | 使用 apksigner 签名 |
| 信息 | `apk_info` | 读取包名、版本、权限等元信息 |
| 证书 | `apk_certificate` | 查看签名证书详情 |
| 权限 | `apk_permissions` | 列出所有声明的权限 |
| Smali | `smali_search` | 在 smali 文件中搜索字符串/方法 |
| Smali | `smali_replace` | 替换 smali 代码 |
| Smali | `smali_hook` | 在方法入口/出口注入代码 |
| 文件 | `file_read/write/list/delete/copy/move` | Shizuku 文件操作 |
| DEX | `dex_read/dex_dump` | 读取 DEX 文件结构 |
| 资源 | `resource_decode/resource_modify` | 解码/修改 resources.arsc |
| XML | `xml_decode/xml_modify` | 解码/修改二进制 XML |
| 字符串 | `string_read/string_modify` | 读取/修改 strings.xml |
| 十六进制 | `hex_read/hex_write/hex_search` | 二进制文件操作 |
| 搜索 | `search_class/search_method/search_string` | 全局搜索 |

**RunNodeScript / PythonExec 集成：**
- 将 Node.js/Python 二进制从 App 私有目录复制到 `/data/local/tmp/`（Shizuku 可访问）
- 通过 Shizuku 执行脚本
- 自动设置权限和环境变量

#### SmaliEngine.kt — Smali 字节码引擎

**核心职责：** 解析、搜索、修改 Smali 字节码文件。

- **Smali 解析**：按行解析 smali 文件，识别方法定义、字段定义、指令
- **模式匹配**：支持正则搜索类名、方法名、字符串常量
- **代码注入**：在方法入口/出口插入自定义指令
- **引用追踪**：查找类/方法的所有引用位置

#### SmartPatcher.kt — 智能补丁引擎

**核心职责：** 自动定位修改点并应用补丁。

- **自动定位**：根据特征码自动找到需要修改的位置
- **补丁生成**：生成 smali 格式的补丁代码
- **冲突检测**：检测补丁是否与现有代码冲突

#### BinaryParser.kt — 通用二进制解析器

- 读取/写入二进制数据
- 支持大端/小端字节序
- 十六进制查看和编辑

#### DEXParser.kt — DEX 文件解析器

**解析 Android DEX 文件格式：**
- header_item：文件头
- string_ids：字符串表
- type_ids：类型表
- method_ids：方法表
- field_ids：字段表
- class_defs：类定义
- 用于 `dex_read`、`dex_dump`、`search_class`、`search_method`、`search_string` 等工具

#### ManifestParser.kt — AndroidManifest.xml 解析器

**解析 AXML 格式的 AndroidManifest.xml：**
- 包名、版本号、版本名
- 所有 Activity/Service/Receiver/Provider 声明
- 权限声明（uses-permission）
- Intent Filter
- meta-data

#### ResourceTableParser.kt — resources.arsc 解析器

**解析 Android 资源表：**
- string_pool：字符串池
- table_entry：资源条目
- package/type/config 结构
- 支持多语言、多配置（横竖屏、dpi 等）

#### XmlParser.kt — AXML 二进制 XML 解析器

**解析 Android 编译后的二进制 XML：**
- XML 声明、元素、属性
- 命名空间
- 资源引用（@type/name）

---

### 3. 高级工具集（apktools/advanced/）

从 AEE (Android Easy Editor) 开源项目移植的 8 个高级工具：

#### ZipAlignTool.kt — APK 对齐优化

- 4096 字节对齐优化
- 使用 zipalign 命令或纯 Kotlin 实现
- 提升 APK 运行时内存映射效率

#### SplitApkHandler.kt — Split APK 处理

- 解析 split APK 结构（base.apk + split_*.apk）
- 合并 split APK 为单一 APK
- 提取 split 信息

#### ResourceDecoder.kt — 资源表深度解码

- 深度解码 resources.arsc
- 输出人类可读的资源定义
- 支持复杂资源类型（数组、样式、主题）

#### ResourceTool.kt — 资源查看器

- 列出所有资源类型和条目
- 按 package/type/filter 查看
- 资源 ID 与名称映射

#### CertificateTool.kt — 增强证书查看器

- 解析 X.509 证书（META-INF/*.RSA/*.DSA/*.EC）
- 显示颁发者、使用者、有效期、序列号、指纹
- 支持 V1/V2/V3 签名方案检测

#### PermissionsTool.kt — 权限查看器

- 列出所有 uses-permission
- 标记 dangerous/signature/normal 保护级别
- 显示权限组信息

#### EnhancedSignTool.kt — 增强签名工具

- 支持 V1 (JAR signing)、V2、V3、V4 签名方案
- 使用调试密钥或自定义密钥签名
- 多种签名方案组合

#### InstallTool.kt — APK 安装器

- 通过 Shizuku 执行 `pm install`
- 支持 `-r`（替换安装）、`-t`（允许测试包）
- 安装结果反馈

---

### 4. 脚本运行器（scripttools/）

#### PythonRunner.kt — Python 执行器

**执行流程：**

```
1. 检查 Python 二进制位置（优先 App 私有目录，备选 /data/local/tmp/）
2. 若在私有目录 → Shizuku cp 到 /data/local/tmp/apkagent_py/
3. chmod +x 设置执行权限
4. 将用户代码写入临时 .py 文件
5. 通过 Shizuku 执行: sh -c "python3 /data/local/tmp/xxx.py"
6. 捕获 stdout/stderr 返回
7. 超时控制（默认 60s）
```

**关键细节：**
- Python 二进制内置在 APK assets 中，首次运行时解压
- 依赖库通过 pip 安装到 `/data/local/tmp/apkagent_py/lib/`
- 若 Python 不可用，自动降级为 shell 命令

#### NodeRunner.kt — Node.js 执行器

- 与 PythonRunner 类似的部署和执行流程
- 支持 npm 模块
- 用于需要 JS 运行时的脚本任务

#### ScriptTools.kt — Shell 脚本工具

**两个主要工具：**
- `create_script`：创建 .sh 脚本文件到 `/sdcard/Download/APKAgent/scripts/`
- `run_script`：通过 Shizuku 执行脚本

**注意：**
- 脚本解释器使用 `sh`（Android 没有 `bash`）
- 参数通过位置变量 `$1 $2 ...` 传递
- 脚本先从工作区复制到 `/data/local/tmp/` 再执行（避免路径权限问题）

---

### 5. Shizuku 集成（shizuku/）

#### ShizukuManager.kt — 提权管理

**核心职责：** 通过 Shizuku 获取 ADB shell (uid 2000) 权限执行命令。

**实现方式：反射调用**

```kotlin
// 核心方法 — 通过反射调用 Shizuku 的 newProcess
object ShizukuManager {
    fun newProcess(command: String): Process {
        val method = ShizukuProvider::class.java
            .getDeclaredMethod("newProcess", Array<String>::class.java)
        return method.invoke(null, arrayOf("sh", "-c", command)) as Process
    }
}
```

**关键设计：**
- 使用**反射调用** `Shizuku.newProcess()`，避免直接依赖导致的编译问题
- 返回标准 `Process` 对象，可读取 stdout/stderr
- 支持超时控制
- 错误码处理（exit code）

**权限检查：**
```kotlin
fun checkPermission(): Boolean {
    return try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }
}
```

**为什么需要 Shizuku：**
- Android 11+ 的 Scoped Storage 限制 App 访问其他 App 的私有目录
- apktool/apksigner 等工具需要文件系统访问权限
- Python/Node 二进制需要在可执行路径运行
- 安装 APK 需要 `pm install` 权限

---

### 6. 网络层（network/）

#### NetworkClient.kt — HTTP 客户端

- 基于 OkHttp 4.x
- 支持 SSE（Server-Sent Events）流式传输
- 用于 AI API 通信
- 请求/响应拦截器（日志、认证）
- 超时配置（连接 15s，读取 120s）

#### NotificationManager.kt — 通知管理

- 前台服务通知
- **ColorOS 流体云适配**（OPPO/OnePlus/realme 设备）
  - 通过反射调用 ColorOS 私有 API
  - 在状态栏显示运行状态
  - 兼容不同 ColorOS 版本

---

### 7. UI 层（ui/）

#### ChatScreen.kt — 主聊天界面

**功能：**
- Telegram 风格的消息气泡
- AI 消息支持 Markdown 渲染（代码块、粗体、列表）
- 工具调用结果展示（折叠/展开）
- 流式打字效果
- 自动滚动到最新消息
- 发送按钮 + 停止按钮

**组件：**
- `MessageBubble.kt`：消息气泡组件
- `ToolCallCard.kt`：工具调用卡片（显示工具名、参数、结果、耗时）
- `CodeBlock.kt`：代码块高亮显示
- `ThinkingIndicator.kt`：AI 思考中的加载动画

#### EditorScreen.kt — 代码编辑器

- Smali 文件语法高亮
- 行号显示
- 搜索/替换
- 保存修改

#### TerminalScreen.kt — 终端模拟器

- 执行 shell 命令
- 输出实时显示
- 命令历史

#### SettingsScreen.kt — 设置界面

- AI Provider 选择（10 个预设 + 自定义）
- API Key 配置（加密存储）
- 模型选择
- 主题切换（11 套主题）
- Shizuku 权限管理

#### WorkspaceScreen.kt — 工作区管理

- 文件浏览器（/sdcard/Download/APKAgent/）
- 查看反编译项目结构
- 快速打开文件

#### HistoryScreen.kt — 对话历史

- 保存所有对话记录
- 按时间排序
- 搜索对话内容
- 恢复历史对话

#### Theme.kt — 11 套主题

| 主题名 | 色调 |
|--------|------|
| Dark | 深灰/黑色 |
| Light | 白色/浅灰 |
| Purple | 紫色渐变 |
| Teal | 蓝绿色 |
| Orange | 橙色 |
| Red | 红色 |
| Pink | 粉色 |
| Indigo | 靛蓝 |
| Mint | 薄荷绿 |
| Sunset | 日落橙红 |
| Cyberpunk | 赛博朋克霓虹 |

---

### 8. 服务层（service/）

#### KeepAliveService.kt — 前台服务保活

**核心职责：** 防止 App 被系统杀死（特别是 OPPO/OnePlus/realme 的 ColorOS 系统）。

**实现：**
- 前台服务 + 持久通知
- WakeLock 防止 CPU 休眠
- **ColorOS 流体云兼容**：通过反射调用 ColorOS 私有 API，在状态栏显示运行状态
- 自动重启机制（`START_STICKY`）

---

## 全部 53 个 AI 工具清单

| # | 工具名 | 描述 | 模块 |
|---|--------|------|------|
| 1 | `apk_decode` | 反编译 APK 到 smali + 资源 | ApkTools |
| 2 | `apk_build` | 重新编译 APK | ApkTools |
| 3 | `apk_sign` | 签名 APK | ApkTools |
| 4 | `apk_install` | 安装 APK 到设备 | ApkTools |
| 5 | `apk_info` | 获取 APK 基本信息 | ApkTools |
| 6 | `apk_certificate` | 查看 APK 签名证书 | ApkTools |
| 7 | `apk_permissions` | 查看 APK 权限列表 | ApkTools |
| 8 | `smali_search` | 搜索 smali 代码 | ApkTools |
| 9 | `smali_replace` | 替换 smali 代码 | ApkTools |
| 10 | `smali_hook` | 在方法入口/出口注入代码 | ApkTools |
| 11 | `file_read` | 读取文件内容 | ApkTools |
| 12 | `file_write` | 写入文件 | ApkTools |
| 13 | `file_list` | 列出目录内容 | ApkTools |
| 14 | `file_delete` | 删除文件 | ApkTools |
| 15 | `file_copy` | 复制文件 | ApkTools |
| 16 | `file_move` | 移动/重命名文件 | ApkTools |
| 17 | `dex_read` | 读取 DEX 文件结构 | ApkTools |
| 18 | `dex_dump` | Dump DEX 类列表 | ApkTools |
| 19 | `resource_decode` | 解码 resources.arsc | ApkTools |
| 20 | `resource_modify` | 修改资源值 | ApkTools |
| 21 | `xml_decode` | 解码二进制 XML | ApkTools |
| 22 | `xml_modify` | 修改 XML 资源 | ApkTools |
| 23 | `string_read` | 读取字符串资源 | ApkTools |
| 24 | `string_modify` | 修改字符串资源 | ApkTools |
| 25 | `hex_read` | 读取十六进制数据 | ApkTools |
| 26 | `hex_write` | 写入十六进制数据 | ApkTools |
| 27 | `hex_search` | 搜索十六进制模式 | ApkTools |
| 28 | `binary_patch` | 二进制补丁 | ApkTools |
| 29 | `search_class` | 搜索 DEX 类名 | ApkTools |
| 30 | `search_method` | 搜索 DEX 方法名 | ApkTools |
| 31 | `search_string` | 搜索 DEX 字符串常量 | ApkTools |
| 32 | `decompile_class` | 反编译单个类 | ApkTools |
| 33 | `apk_zipalign` | APK zipalign 对齐 | Advanced |
| 34 | `apk_split_handler` | Split APK 处理 | Advanced |
| 35 | `apk_decode_resources` | 深度资源解码 | Advanced |
| 36 | `apk_resources` | 资源查看器 | Advanced |
| 37 | `apk_certificate_viewer` | 增强证书查看器 | Advanced |
| 38 | `apk_permissions_viewer` | 权限查看器 | Advanced |
| 39 | `apk_enhanced_sign` | 增强签名（V1-V4） | Advanced |
| 40 | `apk_install_enhanced` | 增强安装器 | Advanced |
| 41 | `run_script` | 执行 Shell 脚本 | ScriptTools |
| 42 | `create_script` | 创建脚本文件 | ScriptTools |
| 43 | `python_exec` | 执行 Python 代码 | ScriptTools |
| 44 | `run_node_script` | 执行 Node.js 脚本 | ScriptTools |
| 45 | `web_fetch` | 获取网页内容 | Network |
| 46 | `web_search` | 搜索网页 | Network |
| 47 | `read_file` | 读取本地文件 | File |
| 48 | `write_file` | 写入本地文件 | File |
| 49 | `list_files` | 列出文件 | File |
| 50 | `search_files` | 搜索文件内容 | File |
| 51 | `terminal_exec` | 执行终端命令 | System |
| 52 | `get_device_info` | 获取设备信息 | System |
| 53 | `shizuku_exec` | Shizuku 提权执行 | System |

---

## 构建与 CI/CD

### 本地构建

```bash
# 克隆仓库
git clone https://github.com/Bailan766/APKAgent.git
cd APKAgent

# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

### GitHub Actions CI/CD

**配置文件：** `.github/workflows/build.yml`

**触发条件：**
- Push 到 `main` 分支
- Pull Request 到 `main` 分支
- 手动触发（workflow_dispatch）

**构建流程：**
1. Checkout 代码
2. Setup JDK 17
3. Setup Android SDK (API 35)
4. 缓存 Gradle 依赖
5. 执行 `./gradlew assembleDebug`
6. 上传 APK 到 GitHub Artifacts
7. **自动创建 GitHub Release 并上传 APK**

**自动 Release 逻辑：**
- 每次成功构建自动创建 Release
- 标签格式：`v{versionName}`
- 自动附加 APK 文件
- 自动生成 Release Notes

### 版本号管理

版本号在 `app/build.gradle.kts` 中定义：

```kotlin
android {
    defaultConfig {
        versionCode = 17      // 每次发布 +1
        versionName = "3.17.0" // 语义化版本号
    }
}
```

---

## 环境要求

### 设备要求

| 项目 | 要求 |
|------|------|
| Android 版本 | 11+ (API 30+) |
| 架构 | arm64-v8a (主要), armeabi-v7a (部分支持) |
| 存储空间 | 至少 500MB 可用空间 |
| Shizuku | 必须安装并授权 |

### Shizuku 配置

1. 安装 [Shizuku](https://shizuku.rikka.app/)
2. 通过 ADB 或 Root 启动 Shizuku
3. 在 APKAgent 中授予 Shizuku 权限

```bash
# 通过 ADB 启动 Shizuku
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

---

## 项目目录结构

```
APKAgent/
├── .github/
│   └── workflows/
│       └── build.yml                    # GitHub Actions CI/CD
├── app/
│   ├── build.gradle.kts                 # App 构建配置
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 应用清单
│   │   ├── assets/                      # 内置资源（Python/Node/apktool 二进制）
│   │   ├── java/com/apkagent/
│   │   │   ├── ApkAgentApp.kt          # Application 初始化
│   │   │   ├── MainActivity.kt         # 主 Activity
│   │   │   ├── ui/                     # Compose UI 层
│   │   │   ├── viewmodel/              # ViewModel 层
│   │   │   └── service/                # 前台服务
│   │   ├── kotlin/com/apkagent/
│   │   │   ├── agent/                  # AI Agent 核心
│   │   │   ├── apktools/               # APK 工具集
│   │   │   │   └── advanced/           # 高级工具
│   │   │   ├── scripttools/            # 脚本运行器
│   │   │   ├── shizuku/                # Shizuku 集成
│   │   │   └── network/                # 网络层
│   │   └── res/                        # Android 资源
│   └── proguard-rules.pro              # 混淆规则
├── build.gradle.kts                     # 根构建配置
├── gradle.properties                    # Gradle 属性
├── settings.gradle.kts                  # 项目设置
└── README.md                           # 本文档
```

---

## 许可证

MIT License

---

## 致谢

- [Shizuku](https://shizuku.rikka.app/) — ADB 提权方案
- [apktool](https://ibotpeaches.github.io/Apktool/) — APK 反编译/编译
- [AEE](https://github.com/nicai-cai/AEE) — 高级工具集参考
- [OkHttp](https://square.github.io/okhttp/) — HTTP 客户端
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI 框架
