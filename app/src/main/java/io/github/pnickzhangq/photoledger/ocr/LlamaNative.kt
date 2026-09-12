// 票 04：llama.cpp JNI 冒烟的 Kotlin 侧包装。
// 生命周期：backendInit -> loadModel -> newContext -> complete()*N -> free*4。
package io.github.pnickzhangq.photoledger.ocr

object LlamaNative {
    init {
        System.loadLibrary("photoledger")
    }

    fun backendInit() = nativeBackendInit()
    fun backendFree() = nativeBackendFree()

    fun loadModel(path: String): Long = nativeLoadModel(path)
    fun freeModel(model: Long) = nativeFreeModel(model)

    fun newContext(model: Long, nCtx: Int = 2048, nThreads: Int = 0): Long =
        nativeNewContext(model, nCtx, nThreads)

    fun freeContext(ctx: Long) = nativeFreeContext(ctx)

    /**
     * 一次式补全。grammar 非空时施加 GBNF 约束（文法来自 engine 的 GrammarGenerator）。
     * nLen 为最大生成长度；KV 在返回前清空，context 可复用。
     */
    fun complete(ctx: Long, prompt: String, grammar: String = "", nLen: Int = 256): String =
        nativeComplete(ctx, prompt, grammar, nLen)

    private external fun nativeBackendInit()
    private external fun nativeBackendFree()
    private external fun nativeLoadModel(filename: String): Long
    private external fun nativeFreeModel(model: Long)
    private external fun nativeNewContext(model: Long, nCtx: Int, nThreads: Int): Long
    private external fun nativeFreeContext(ctx: Long)
    private external fun nativeComplete(ctx: Long, prompt: String, grammar: String, nLen: Int): String
}
