# APKAgent

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="120" alt="APKAgent Logo"/>
</p>

<p align="center">
  <b>AI 驱动的 Android APK 逆向分析工具</b><br>
  用自然语言对话，让 AI 自动完成 APK 分析、反编译、签名检测与修改
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-green"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-blue"/>
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material3-purple"/>
  <img alt="License" src="https://img.shields.io/badge/License-MIT-orange"/>
</p>

---

## 简介

APKAgent 是一款**原生 Android 应用**，将 MT 管理器式的 APK 逆向能力与 AI Agent 对话操控深度融合。你只需用自然语言描述需求，AI 便会自动调用内置工具完成 APK 分析、反编译、签名检测与 patch。

**核心理念：** 不需要记忆命令，不需要懂 smali 语法，用中文告诉 AI 你想做什么，它自己搞定。

---

## 功能特性

### 🤖 AI Agent 对话操控
- 兼容 **OpenAI 协议**的任意 API（DeepSeek、Qwen、Claude、本地 Ollama 等）
- **自定义 API Base URL、API Key、模型名、温度**
- 流式 SSE 输出，实时显示 AI 推理过程
- **Function Calling** 驱动的 Agent 循环：AI 决策 → 调用工具 → 结果回传 → 继续推理
- 敏感操作弹窗确认，AI 不会静默修改文件

### 🔍 APK 分析
| 工具 | 功能 |
|------|------|
| `apk_apk_info` | APK 概览（包名、版本、SDK、DEX 数、权限、组件） |
| `apk_list_files` | 浏览 APK 内部 ZIP 结构 |
| `apk_read_manifest` | 解析二进制 AndroidManifest.xml |
| `apk_read_signer` | 检测 v1/v2/v3 签名方案，提取 X.509 证书 |
| `apk_read_dex` | 解析 DEX 头 + 列出所有类名 |
| `apk_hex_view` | 十六进制查看任意文件或 APK 内条目 |
| `apk_extract_entry` | 从 APK 提取指定文件到工作区 |

### 🔧 逆向工程
| 工具 | 功能 |
|------|------|
| `dex_disasm` | DEX → smali 反编译（Google baksmali 3.0.9） |
| `dex_assemble` | smali → DEX 回编译（Google smali 3.0.9） |
| `dex_edit` | 查看 smali 方法/字段列表，在线编辑 |
| `apk_remove_signature_check` | 扫描签名校验调用点 + 自动 patch（返回 true） |
| `apk_unpack` | 解包 APK 到工作区目录 |
| `apk_repack` | 重打包修改后的目录为 APK |
| `apk_sign` | v1(PKCS#7) + v2(APK Sig Scheme v2) 重签名 |

### 📁 文件管理
| 工具 | 功能 |
|------|------|
| `file_read_text` | 读取工作区文本文件 |
| `file_list` | 列出工作区目录 |
| `file_write` | 写入工作区文件（带沙箱保护） |

### ✏️ 可视化编辑器
- 工作区目录树浏览
- smali / XML / 文本文件在线查看与编辑
- 保存修改直接写回文件

### 🛡️ 沙箱安全
- 所有文件工具路径限制在 App 工作区内，拒绝越权访问
- 敏感操作（写/改/提取/签名）需用户弹窗确认

---

## 技术栈

| 模块 | 技术 |
|------|------|
| UI | Kotlin 2.0 + Jetpack Compose (Material3) |
| AI 通信 | OkHttp 4.12 SSE 流式 + Function Calling |
| Smali 引擎 | Google smali/baksmali/dexlib2 3.0.9 |
| APK 签名 | Google apksig 8.7.0 |
| 证书生成 | BouncyCastle 1.78.1 |
| 配置存储 | EncryptedSharedPreferences（AES256-GCM，降级容错） |
| AXML 解析 | 纯 Kotlin 自实现（无外部依赖） |
| DEX 解析 | 纯 Kotlin 自实现 + dexlib2 |
| 签名读取 | JarFile(v1) + 手动解析 APK Signing Block(v2/v3) |

---

## 安装要求

- Android 8.0+（API 26）
- 架构：arm64-v8a / armeabi-v7a / x86_64

---

## 快速开始

### 1. 安装
下载 `APKAgent-debug.apk` 安装，允许"来自未知来源"。

### 2. 配置 AI
点击右上角 ⚙ 进入设置：
```
API Base URL : https://api.deepseek.com/v1
API Key      : sk-xxxxxxxxxxxx
模型名        : deepseek-chat
温度          : 0.6
```
支持任何 OpenAI 兼容接口。

### 3. 导入 APK
点击顶栏 📁 选择目标 APK 文件。

### 4. 对话分析
直接用中文提需求，例如：

```
分析这个 APK 的签名信息
列出 AndroidManifest 里的所有权限
把 classes.dex 反编译成 smali 并列出类
扫描签名校验调用点并 patch
```

---

## 完整逆向流程示例

以"去除签名校验"为例，只需告诉 AI：

> **"分析这个 APK，找出签名校验的地方，帮我 patch 掉"**

AI 会自动执行：
1. `apk_apk_info` — 了解 APK 基本信息
2. `apk_read_signer` — 读取签名方案
3. `apk_remove_signature_check` (scan) — 扫描校验调用点
4. `apk_remove_signature_check` (patch) — 反编译+插入 return true+回编译
5. `apk_repack` — 重新打包
6. `apk_sign` — v1+v2 重签名

全程 AI 自动推进，你只需在敏感操作弹窗点"允许"。

---

## 项目结构

```
APKAgent/
├── app/src/main/java/com/apkagent/
│   ├── MainActivity.kt              # 入口 + 导航
│   ├── ApkAgentApp.kt               # Application 类
│   ├── agent/
│   │   ├── Models.kt                # OpenAI 数据模型
│   │   ├── OpenAIClient.kt          # 流式 SSE + Function Calling
│   │   ├── AgentLoop.kt             # Agent 推理循环
│   │   ├── Tool.kt                  # 工具接口定义
│   │   └── ToolRegistry.kt          # 工具注册表
│   ├── apktools/
│   │   ├── AxmlParser.kt            # 二进制 XML 解析器
│   │   ├── DexParser.kt             # DEX 头解析器
│   │   ├── HexView.kt               # 十六进制查看
│   │   ├── SignatureReader.kt        # v1/v2/v3 签名读取
│   │   ├── Sandbox.kt               # 路径沙箱
│   │   ├── ApkTools.kt              # 17 个工具实现
│   │   └── smali/
│   │       ├── SmaliEngine.kt        # 反编译/回编译引擎
│   │       ├── SignatureCheckerScanner.kt  # 签名校验扫描+patch
│   │       ├── ApkRepackSigner.kt    # 重打包+签名
│   │       ├── DebugKeyProvider.kt   # 调试密钥生成
│   │       └── SelfSignedCertGenerator.kt  # 证书生成
│   ├── store/
│   │   └── SettingsStore.kt         # 加密配置存储
│   └── ui/
│       ├── ChatScreen.kt            # 主聊天界面
│       ├── ChatViewModel.kt         # 聊天逻辑
│       ├── SettingsScreen.kt        # 设置页
│       ├── EditorScreen.kt          # 可视化编辑器
│       ├── MarkdownText.kt          # Markdown 渲染
│       ├── Components.kt            # 工具卡片、权限弹窗
│       └── Theme.kt                 # 主题配置
├── app/build.gradle.kts
├── gradle/libs.versions.toml
└── settings.gradle.kts
```

---

## 构建

```bash
# 环境要求：JDK 17+，Android SDK 34
git clone <repo>
cd APKAgent
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 注意事项

- APK 仅使用 debug 签名，安装需允许未知来源
- API Key 通过 AES256-GCM 加密存储于本机，不上传任何服务器
- 所有文件操作限制在 App 私有工作区，不影响其他应用
- 逆向工具仅供安全研究、漏洞分析、合法授权测试使用

---

## License

MIT License — 详见 [LICENSE](LICENSE)
