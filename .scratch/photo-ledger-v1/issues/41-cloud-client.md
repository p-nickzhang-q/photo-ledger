# 票 41：云端客户端与图片预处理

- Owner: agent（QoderCN）
- Created: 2026-11-18
- Status: Resolved

## Resolved

- 结论（2026-11-18）：新增 `CloudConfig` / `CloudPrefs`、`CloudVisionClient`（可注入 post）、`ImagePreprocessor`。
- 证据：CloudVisionClientTest 全绿（假 post，不触网）。覆盖成功解析、URL/鉴权/data URL 请求形态、Key 为空不发 Authorization、401/403、5xx、非 JSON、空订单、超时、非 https、usage 解析、测试连接成功与失败。
- 真机（OpenCode Go / deepseek-v4.1-flash）：测试连接成功；合成订单图识别成功（7.7s，usage 输入 1,259 / 输出 335 / 总计 1,594 tokens）；图片压缩与上传链路验证通过。
- 兼容：OpenCode Go 网关要求 `x-opencode-session` 头（缺失返回 400 MissingSessionID）。客户端仅在 `opencode.ai` 端点附加进程内随机 UUID 会话头，避免向其它兼容服务发送未知头。非 2xx 会带出服务端 `error.message`（截断 120 字），便于定位 400/401。

## 方案

- 请求：`POST {base}/chat/completions`，仅 https；body 为 `model + temperature=0 + messages[text + image_url(data:image/jpeg;base64,…)]`；连接 15s / 读 60s。
- 响应：兼容 `choices[0].message.content` 为字符串 / 文本数组 / 对象三形态，再交给 CloudDraftParser。
- 错误：统一 `CloudException`，文案区分 Key 无效、超时、HTTP n、非 JSON、解析失败；**不记录 Key / 请求体 / 截图内容**。
- 图片：bounds 解码 + inSampleSize + 长边 1568 + JPEG 85 + recycle。
- 配置：`SharedPreferences("cloud")` 存 engine / base_url / model / api_key / consent_done。
- Key 不随备份外泄：`backup_rules.xml`（API ≤30）与 `data_extraction_rules.xml`（API 31+）排除 `sharedpref/cloud.xml`。

## 验收

- 客户端单测覆盖错误分支与请求形态。
- 真机：配置真实接口后 `测试连接` 成功；压缩后请求体在常见接口上限内。
