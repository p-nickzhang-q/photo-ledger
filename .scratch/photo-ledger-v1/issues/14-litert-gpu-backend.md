# 票 14：LiteRT GPU 后端启用——真机速度二轮优化（40.7s → 16.8s）

**Status:** resolved

**Owner:** p-nickzhang-q

**Created:** 2026-09-16

**Resolved:** 2026-09-16

---

## 背景

票 13 LiteRT CPU 后端 41.9s 达「可用线」，但用户要求 10s 级。GPU 是下一个已知杠杆（官方基准 vivo 同厂 GPU 580 prefill / 21 decode tok/s，vs CPU 165/9）。票 13 时 GPU 失败被误判为「vivo 封闭 OpenCL」。

## 目标

- [x] 修复 GPU 后端初始化失败，真机跑通 LiteRT GPU
- [x] 真机端到端进入可接受区间（10s 未达，40.7s→16.8s）
- [x] GPU 路径质量验证（与桌面 CPU 闸门结果一致性）
- [x] INT4 GPU 优化档评估（结论：上游 bug，弃用留观）

## Comments

**2026-09-16 — 根因修复（两项，均与票 13 误判相关）**

1. **OpenCL 解锁**：票 13 报 "Can not find OpenCL library" 实为 Android API 24+ linker namespace 拦截非公开系统库，与 vivo 无关。manifest 一行修复：
   ```xml
   <uses-native-library android:name="libOpenCL.so" />
   ```
   （教训：曾写成 `<uses-native-library android:name="libOpenCL-pixel.so" required="false"/>`——`required` 缺 `android:` 前缀被当未知属性取默认 true，安装直接失败 INSTALL_FAILED_MISSING_SHARED_LIBRARY。Pixel 专用库，本机不需要，直接删。）

2. **GPU 采样器缺失**：GPU 路径 logcat 有 `Could not load shared library libLiteRtTopKOpenClSampler.so`，回退静态 CPU 采样器。**回退采样器与 LLGuidance 约束解码不兼容 → 输出稳定截断在 145 字符**（两次运行 byte-identical）。修复：从 LiteRT-LM 仓库 `prebuilt/android_arm64/`（v0.17.0 tag，与 AAR 版本对应）下载 `libLiteRtTopKOpenClSampler.so`，NDK llvm-strip 后（11.8MB→5.0MB）放 `app/src/main/jniLibs/arm64-v8a/` 打包。

**2026-09-16 — 实测数据（vivo V2183A 天玑9000+，Mali-G710，order_01/02）**

| 配置 | 加载(热) | LLM | 端到端 | 结果 |
|---|---|---|---|---|
| INT8 CPU（票 13 基线） | 0.35s | 40.7s | 41.9s | ✅ 4/4 |
| INT8 GPU | 3.8-4.1s | 15.7-16.8s | **17.3-18.1s** | ✅ 4/4，与桌面 CPU 一致 |
| INT4 GPU（wi4b32） | 5.4s | **5.5s** | ~7s | ❌ 固定 145 字符截断 |
| INT4 CPU（桌面闸门） | - | - | - | ❌ 30/30 同型截断 |

- GPU 采样器质量等价性：order_01/02 真机 GPU 与桌面 CPU INT8 结果完全一致（2/2）。30 张闸门无法在真机跑（太慢）/桌面无 OpenCL——等价性证据为 2 张对照 + 采样器只改采样实现不改分布（top-1 贪心）。
- INT4 截断为上游 bug：LiteRT-LM issue [#3577](https://github.com/google-ai-edge/LiteRT-LM/issues/3577)（open）同版本同现象（dynamic_wi4* 档输出确定性偏离/截断，INT8 wi8 档同 GPU 正常）；[#2703](https://github.com/google-ai-edge/LiteRT-LM/issues/2703) 约束解码下 premature kDone。v0.17.0 无解。
- INT4 速度潜力证实：LLM 5.5s（INT8 的 1/3）。上游修复或用修复版 quantizer 自转换后可再提速（留票 10 后评估）。
- 10s 目标未达：decode ~6 tok/s（官方 vivo X300 Pro 21 tok/s，Mali-G710 落后一代 + 真机热节流 67°C）。剩余手段：prompt 缩短（闸门否决过）、输出字段缩短（动 Draft 契约，成本高）、上游 runtime 升级。17-19s 配合票 07 队列异步化可接受。

**2026-09-16 — 附带修复与基建**

- 冒烟入口：`handleSmokeIntent` 抽出 + `onNewIntent` 接入。App 存活时重发 smoke intent 不再被忽略（调试期反复撞上的「intent 无效」根因——onCreate 才读 extras）。`SMOKE_INTENT` 日志落入口。
- `litert_backend` intent extra：smoke 场景强制 cpu/gpu 后端（A/B 对照不用重装）。
- CLI `--litert-backend` 参数：litert 闸门 transport 后端可选（桌面 WSL 无 OpenCL，真机闸门走 App）。
- 陷阱记录：**scanModelDir 取「最后一个 .litertlm」**——备份文件改名留在 files 目录（如 `_bak_int4.litertlm`）会被误选为当前模型（速度特征明显不同可发现：LLM 5s vs 17s）。备份必须挪出 files 目录。

**2026-09-16 — 决策**

用户拍板方案 1：**INT8+GPU 定稿**（官方支持矩阵组合，质量已验证）；INT4 重转/runtime 升级留票 10（模型管理）后评估。本票收口。
