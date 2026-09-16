# Photo Ledger

## Build & tooling（环境硬约束）

- **App 模块构建必须走 Windows 侧 Gradle**（SDK 在 `D:\Android\Sdk`，无 Linux build-tools，WSL 侧跑 `:app` 任务报 "Build Tools corrupted"）：
  `JAVA_HOME='/mnt/d/Program Files/Java/ms-21.0.7' WSLENV='JAVA_HOME/p' cmd.exe /c "gradlew.bat :app:assembleDebug"`
- WSL 侧（`JAVA_HOME=~/.jdks/ms-21.0.9 ANDROID_HOME=/mnt/d/Android/Sdk`）只能跑纯 JVM 模块：`:engine:test`、`:cli:run`（闸门）
- adb 不在 PATH：`/mnt/d/Android/Sdk/platform-tools/adb.exe`（Windows 版，WSL 直接调用）
- 模型文件放 `models/`（gitignore），真机推 `/sdcard/Android/data/<pkg>/files/`；下载用国内源：ModelScope（`modelscope.cn/models/litert-community/<repo>/resolve/master/<file>`，稳）或 hf-mirror（抽风）

## Agent skills

### Issue tracker

Issues 以本地 markdown 文件存在 `.scratch/` 下（一个 feature 一个目录）。See `docs/agents/issue-tracker.md`.

### Triage labels

五个默认 triage 角色，标签字符串与角色同名。See `docs/agents/triage-labels.md`.

### Domain docs

Single-context：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.
