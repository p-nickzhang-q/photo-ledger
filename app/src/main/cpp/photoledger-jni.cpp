// 票 04：llama.cpp 文本推理 JNI 冒烟。
// 设计参照 third_party/llama.cpp/examples/llama.android（batch 助手 / UTF-8 处理），
// 简化为一次式 complete()：GBNF 约束下输出是短 JSON，无需流式回调。
#include <algorithm>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include "llama.h"
#include "common.h"
#include "ggml.h"

#define TAG "photoledger-jni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", fmt);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeLoadModel(JNIEnv *env, jobject, jstring filename) {
    const auto * path = env->GetStringUTFChars(filename, nullptr);
    LOGi("loading model: %s", path);
    llama_model_params mparams = llama_model_default_params();
    auto * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(filename, path);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "llama_model_load_from_file failed");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeFreeModel(JNIEnv *, jobject, jlong model) {
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeNewContext(JNIEnv *env, jobject, jlong jmodel, jint nCtx, jint nThreads) {
    auto * model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "model is null");
        return 0;
    }
    if (nThreads <= 0) {
        // 大小核 SoC：优先只用性能核。1×3.05GHz + 3×2.85GHz + 4×1.8GHz（真机实测），
        // 8-2=6 会踩 2 个能效核拖慢矩阵乘，取 min(4, 在线核数) 纯大核。
        nThreads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 4));
    }
    LOGi("new context: n_ctx=%d threads=%d", nCtx, nThreads);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) nCtx;
    cparams.n_threads       = (int) nThreads;
    cparams.n_threads_batch = (int) nThreads;
    auto * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "llama_init_from_model failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeFreeContext(JNIEnv *, jobject, jlong ctx) {
    llama_free(reinterpret_cast<llama_context *>(ctx));
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeBackendInit(JNIEnv *, jobject) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeBackendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

// 一次式补全：tokenize -> prefill -> 贪心(+GBNF) 解码循环 -> 拼完整文本返回。
// grammar 非空时加 llama_sampler_init_grammar（GBNF 来自 engine 的 GrammarGenerator）。
extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_pnickzhangq_photoledger_ocr_LlamaNative_nativeComplete(
        JNIEnv *env, jobject,
        jlong jctx, jstring jprompt, jstring jgrammar, jint nLen) {
    auto * ctx = reinterpret_cast<llama_context *>(jctx);
    if (!ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "ctx is null");
        return nullptr;
    }
    const auto * vocab = llama_model_get_vocab(llama_get_model(ctx));

    const auto * prompt = env->GetStringUTFChars(jprompt, nullptr);
    const std::string prompt_str(prompt);
    const auto tokens = common_tokenize(vocab, prompt_str, true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    if (tokens.empty()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "tokenize produced no tokens");
        return nullptr;
    }
    const int n_prompt = (int) tokens.size();
    if (n_prompt + nLen > (int) llama_n_ctx(ctx)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "prompt + nLen exceeds n_ctx");
        return nullptr;
    }

    llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    common_batch_clear(batch);
    for (int i = 0; i < n_prompt; i++) {
        common_batch_add(batch, tokens[i], i, { 0 }, i == n_prompt - 1);
    }
    // 只对最后一个 prompt token 要 logits
    batch.logits[batch.n_tokens - 1] = true;
    const int64_t t_prefill0 = ggml_time_ms();
    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "prefill llama_decode failed");
        return nullptr;
    }
    const int64_t prefill_ms = ggml_time_ms() - t_prefill0;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    const auto * grammar = env->GetStringUTFChars(jgrammar, nullptr);
    if (grammar != nullptr && grammar[0] != '\0') {
        llama_sampler_chain_add(smpl, llama_sampler_init_grammar(vocab, grammar, "root"));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    out.reserve(256);
    const int64_t t_gen0 = ggml_time_ms();
    int n_generated = 0;
    for (int n_cur = n_prompt; n_cur < n_prompt + nLen; n_cur++) {
        const llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        out += common_token_to_piece(vocab, id, true);
        n_generated++;

        llama_batch next = llama_batch_init(1, 0, 1);
        common_batch_clear(next);
        common_batch_add(next, id, n_cur, { 0 }, true);
        const int rc = llama_decode(ctx, next);
        llama_batch_free(next);
        if (rc != 0) {
            LOGe("decode failed at pos %d", n_cur);
            break;
        }
    }
    // token 级打点：定位耗时是 prefill 还是解码、解码了多少 token（票10 优化依据）
    const int64_t gen_ms = ggml_time_ms() - t_gen0;
    LOGi("COMPLETE stats: n_prompt=%d prefill_ms=%lld n_generated=%d gen_ms=%lld (%.2f tok/s)",
         n_prompt, (long long) prefill_ms, n_generated, (long long) gen_ms,
         gen_ms > 0 ? n_generated * 1000.0 / gen_ms : 0.0);
    LOGi("COMPLETE output: %s", out.c_str());

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    env->ReleaseStringUTFChars(jgrammar, grammar);
    // 清 KV，供下一次请求复用同一 context
    llama_memory_clear(llama_get_memory(ctx), true);
    return env->NewStringUTF(out.c_str());
}
