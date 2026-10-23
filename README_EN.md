# APKAgent

<p align="center">
  <b>Project-based APK reverse-engineering workbench</b><br>
  Native Android toolkit for APK analysis, project management, AI collaboration, and patched APK export
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-green"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-blue"/>
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material3-purple"/>
  <img alt="Version" src="https://img.shields.io/badge/version-4.0.0-orange"/>
  <a href="./README.md">中文文档</a>
</p>

---

## Overview

APKAgent is not just an “APK chat app”. It is a **project-based reverse-engineering workbench**:

- Import an APK or installed app → create an isolated project directory
- Auto-generate a pre-analysis summary so the AI is not working from an empty context
- Drive analysis, decompile, patch, repack, and sign via Agent + tool calling
- Keep project history, files, script runtimes, and an export loop in one place

Goal: turn one-off APK ops into a **manageable project workflow**.

---

## Language / i18n

The UI and AI system prompt support **Chinese** and **English**.

- Open **Settings → Language**
- Choices: *System default* / *中文* / *English*
- Default follows the device locale (`zh*` → Chinese, otherwise English)
- Switching language updates UI strings immediately; the agent system prompt follows the same preference so replies match the UI language

> Note: many low-level tool result messages are still Chinese. The English system prompt tells the model to keep answering the user in English.

---

## Product shape

1. **Project workspace** — `source/`, `analysis/`, `chat/`, `exports/`, `patches/`, `scripts/`
2. **Pre-analysis first** — package/version/SDK, permissions, DEX/native/assets, certs, basic risk hints
3. **AI Agent + tools** — OpenAI-compatible APIs for manifest/DEX/sign/patch/repack
4. **Runtimes** — Python, Node.js, Shizuku, built-in terminal (`RuntimeManager`)
5. **Sensitive-op confirmation** — DANGER/CAUTION tools require explicit UI approval

---

## Build

```bash
cd APKAgent
./gradlew test
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Permissions model

This is a power-user reverse toolkit (not a store-minimal sandbox app):

- `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`
- Foreground service / wake lock for long jobs
- Optional Shizuku for privileged shell/runtime

---

## License

MIT License
