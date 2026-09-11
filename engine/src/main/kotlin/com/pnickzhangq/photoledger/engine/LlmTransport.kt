package com.pnickzhangq.photoledger.engine

/**
 * LLM 传输抽象：引擎核心只依赖此接口，不感知 llama-server / JNI 的差异。
 *
 * 桌面 POC 用 llama-server HTTP 实现（engine-llamacpp）；
 * Android（票 04）提供 JNI 进程内实现，引擎代码不动。
 */
interface LlmTransport {
    /**
     * 发送一张图 + 提取 prompt，返回模型原始文本输出（约束解码下应为合法 JSON）。
     * 实现负责编码（HTTP / JNI）与解码循环；抛 [RuntimeException] 表示传输层故障。
     */
    suspend fun complete(imageData: ByteArray, imageMime: String, prompt: String, grammar: String): String

    /**
     * 纯文本调用（OCR 路线，票 12）：无图像，prompt 即完整输入。
     */
    suspend fun completeText(prompt: String, grammar: String): String =
        complete(ByteArray(0), "text/plain", prompt, grammar)
}
