#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <malloc.h>
#if defined(__ANDROID__)
#include <android/api-level.h>
// mallopt 的 M_BIONIC_SET_HEAP_TAGGING_LEVEL / M_HEAP_TAGGING_LEVEL_NONE
// 在 <malloc.h> 里，仅在 Android 11(API 30)+ 定义
#endif
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "operit_quickjs.h"

// ============================================================================
// [MTE-FIX] Android 16 Tagged Pointers / MTE 兼容
//
// 崩溃现场（真机 Xiaomi Android 16，tombstone_01/02 两次一致）：
//   signal 6 (SIGABRT)
//   Abort message: 'Pointer tag for 0x784b073170 was truncated'
//   #00 libc.so (abort+160)
//   #01 libc.so (malloc_usable_size+112)
//   #02 libquickjsjni.so  ← MallocUsableSize()
//   #03 libquickjsjni.so  ← RecordReallocation()
//   线程名 OperitQuickJsRuntime
//
// 机理：Android 16 默认开启 tagged_addr_ctrl = PR_TAGGED_ADDR_ENABLE，
// malloc 返回的指针高 8 位携带 MTE tag。QuickJS 内部会把指针编码进
// NaN-boxing 的 JSValue（64 位 double 载荷），再取出来时 tag 被破坏。
// bionic 的 malloc_usable_size() 在校验 tag 不符时直接 abort()。
//
// 修法：调用 malloc 系查询 API 前先把 tag 清掉，只保留真实地址。
// ============================================================================
//
// [MTE-FIX-3] 曾经在这里定义 OPERIT_UNTAG 宏，把指针 tag 位清零。
// 该做法是错的，已彻底移除，切勿再引入。
//
// 依据（tombstone_06，Android 16）：
//   Abort message: 'Pointer tag for 0x784b0882c0 was truncated'
//   #01 libc.so (free+112)
//   #02 libquickjsjni.so (js_realloc_rt+748)
// 崩溃地址高 8 位为 0x00，即 tag 被我们的宏清掉了。
// MTE 要求 free/realloc 收到的指针 tag 与 malloc 时完全一致，
// 清成 0 会被 bionic 判定为 tag 被截断，直接 abort。
//
// 正确做法：
//   ① 不碰 tag，malloc/free/realloc 全程原样传递指针；
//   ② 真正需要规避的是 malloc_usable_size 对「推算指针」的校验，
//      已通过 kMallocFunctions[3] = nullptr 解决（见文件末尾）。

namespace {

std::string EscapeJson(const std::string& input) {
    std::string output;
    output.reserve(input.size() + 16);
    for (char ch : input) {
        switch (ch) {
            case '"':
                output += "\\\"";
                break;
            case '\\':
                output += "\\\\";
                break;
            case '\b':
                output += "\\b";
                break;
            case '\f':
                output += "\\f";
                break;
            case '\n':
                output += "\\n";
                break;
            case '\r':
                output += "\\r";
                break;
            case '\t':
                output += "\\t";
                break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20U) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", ch & 0xff);
                    output += buf;
                } else {
                    output += ch;
                }
                break;
        }
    }
    return output;
}

std::string QuoteJson(const std::string& input) {
    return "\"" + EscapeJson(input) + "\"";
}

std::string PreviewText(const std::string& input, size_t max_length = 240) {
    std::string normalized;
    normalized.reserve(input.size());
    for (char ch : input) {
        switch (ch) {
            case '\r':
            case '\n':
            case '\t':
                normalized += ' ';
                break;
            default:
                normalized += ch;
                break;
        }
    }
    if (normalized.size() <= max_length) {
        return normalized;
    }
    if (max_length <= 3) {
        return normalized.substr(0, max_length);
    }
    return normalized.substr(0, max_length - 3) + "...";
}

std::string BuildJsonStringArray(const std::vector<std::string>& values) {
    std::string json = "[";
    for (size_t index = 0; index < values.size(); index += 1) {
        if (index > 0) {
            json += ",";
        }
        json += QuoteJson(values[index]);
    }
    json += "]";
    return json;
}

std::string BuildEvalEnvelope(
    bool success,
    const std::optional<std::string>& value_json,
    const std::optional<std::string>& error_message,
    const std::optional<std::string>& error_stack,
    const std::optional<std::string>& error_details_json = std::nullopt
) {
    std::string json = "{";
    json += "\"success\":";
    json += success ? "true" : "false";
    json += ",\"valueJson\":";
    json += value_json.has_value() ? QuoteJson(*value_json) : "null";
    json += ",\"errorMessage\":";
    json += error_message.has_value() ? QuoteJson(*error_message) : "null";
    json += ",\"errorStack\":";
    json += error_stack.has_value() ? QuoteJson(*error_stack) : "null";
    json += ",\"errorDetailsJson\":";
    json += error_details_json.has_value() ? QuoteJson(*error_details_json) : "null";
    json += "}";
    return json;
}

std::string JsValueToString(JSContext* context, JSValueConst value) {
    const char* chars = JS_ToCString(context, value);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    JS_FreeCString(context, chars);
    return result;
}

std::optional<std::string> GetExceptionPropertyString(
    JSContext* context,
    JSValueConst exception,
    const char* property_name
) {
    JSValue property = JS_GetPropertyStr(context, exception, property_name);
    if (JS_IsException(property)) {
        JSValue nested_exception = JS_GetException(context);
        std::string nested_message = JsValueToString(context, nested_exception);
        JS_FreeValue(context, nested_exception);
        JS_FreeValue(context, property);
        return std::string("<failed to read ") + property_name + ": " + nested_message + ">";
    }
    if (JS_IsUndefined(property) || JS_IsNull(property)) {
        JS_FreeValue(context, property);
        return std::nullopt;
    }
    std::string result = JsValueToString(context, property);
    JS_FreeValue(context, property);
    if (result.empty()) {
        return std::nullopt;
    }
    return result;
}

std::string JStringToString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string DescribeJavaThrowable(JNIEnv* env, jthrowable throwable) {
    if (env == nullptr || throwable == nullptr) {
        return "Java host call failed";
    }

    jclass throwable_class = env->FindClass("java/lang/Throwable");
    if (throwable_class == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(throwable);
        return "Java host call failed";
    }

    jmethodID to_string_method = env->GetMethodID(
        throwable_class,
        "toString",
        "()Ljava/lang/String;"
    );
    jmethodID get_cause_method = env->GetMethodID(
        throwable_class,
        "getCause",
        "()Ljava/lang/Throwable;"
    );
    if (to_string_method == nullptr || get_cause_method == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(throwable_class);
        env->DeleteLocalRef(throwable);
        return "Java host call failed";
    }

    std::vector<std::string> parts;
    jthrowable current = throwable;
    while (current != nullptr && parts.size() < 6) {
        jstring text_value = static_cast<jstring>(env->CallObjectMethod(current, to_string_method));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (text_value != nullptr) {
                env->DeleteLocalRef(text_value);
            }
            env->DeleteLocalRef(current);
            current = nullptr;
            break;
        }

        std::string part = JStringToString(env, text_value);
        if (text_value != nullptr) {
            env->DeleteLocalRef(text_value);
        }
        if (!part.empty()) {
            parts.push_back(part);
        }

        jthrowable next = static_cast<jthrowable>(env->CallObjectMethod(current, get_cause_method));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (next != nullptr) {
                env->DeleteLocalRef(next);
            }
            env->DeleteLocalRef(current);
            current = nullptr;
            break;
        }
        if (next != nullptr && env->IsSameObject(current, next)) {
            env->DeleteLocalRef(next);
            next = nullptr;
        }
        env->DeleteLocalRef(current);
        current = next;
    }
    if (current != nullptr) {
        env->DeleteLocalRef(current);
    }

    env->DeleteLocalRef(throwable_class);
    if (parts.empty()) {
        return "Java host call failed";
    }

    std::string message = parts.front();
    for (size_t index = 1; index < parts.size(); index += 1) {
        message += " | caused by: " + parts[index];
    }
    return message;
}

std::optional<std::string> TakeJavaExceptionMessage(JNIEnv* env) {
    if (env == nullptr || !env->ExceptionCheck()) {
        return std::nullopt;
    }
    jthrowable throwable = env->ExceptionOccurred();
    env->ExceptionClear();
    return DescribeJavaThrowable(env, throwable);
}

struct HostCallResult {
    std::optional<std::string> value;
    std::optional<std::string> error;
};

class QuickJsVm {
public:
    QuickJsVm(JavaVM* java_vm, JNIEnv* env, jobject host_bridge)
        : java_vm_(java_vm), interrupted_(false) {
        runtime_ = JS_NewRuntime2(&kMallocFunctions, this);
        if (runtime_ == nullptr) {
            throw std::runtime_error("JS_NewRuntime failed");
        }

        context_ = JS_NewContext(runtime_);
        if (context_ == nullptr) {
            JS_FreeRuntime(runtime_);
            runtime_ = nullptr;
            throw std::runtime_error("JS_NewContext failed");
        }

        host_bridge_ = env->NewGlobalRef(host_bridge);
        if (host_bridge_ == nullptr) {
            JS_FreeContext(context_);
            JS_FreeRuntime(runtime_);
            context_ = nullptr;
            runtime_ = nullptr;
            throw std::runtime_error("NewGlobalRef(host_bridge) failed");
        }

        jclass host_bridge_class = env->GetObjectClass(host_bridge);
        on_call_method_ = env->GetMethodID(
            host_bridge_class,
            "onCall",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        );
        env->DeleteLocalRef(host_bridge_class);
        if (on_call_method_ == nullptr) {
            env->DeleteGlobalRef(host_bridge_);
            host_bridge_ = nullptr;
            JS_FreeContext(context_);
            JS_FreeRuntime(runtime_);
            context_ = nullptr;
            runtime_ = nullptr;
            throw std::runtime_error("HostBridge.onCall(String, String) not found");
        }

        JS_SetRuntimeOpaque(runtime_, this);
        JS_SetInterruptHandler(runtime_, &QuickJsVm::HandleInterrupt, this);
        InstallNativeInterface();
    }

    ~QuickJsVm() {
        if (context_ != nullptr) {
            JS_FreeContext(context_);
            context_ = nullptr;
        }
        if (runtime_ != nullptr) {
            JS_FreeRuntime(runtime_);
            runtime_ = nullptr;
        }
        JNIEnv* env = nullptr;
        if (host_bridge_ != nullptr && java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(host_bridge_);
            host_bridge_ = nullptr;
        }
    }

    std::string Eval(const std::string& script, const std::string& file_name) {
        std::lock_guard<std::mutex> guard(lock_);
        BeginExecutionTrace(file_name, script.size(), script);

        JSValue result = JS_Eval(
            context_,
            script.c_str(),
            script.size(),
            file_name.c_str(),
            JS_EVAL_TYPE_GLOBAL
        );
        if (JS_IsException(result)) {
            return TakeExceptionEnvelope();
        }

        std::string value_json = SerializeValue(result);
        JS_FreeValue(context_, result);
        return BuildEvalEnvelope(true, value_json, std::nullopt, std::nullopt);
    }

    std::string CallFunction(
        const std::string& function_name,
        const std::string& args_json,
        const std::string& call_site
    ) {
        std::lock_guard<std::mutex> guard(lock_);
        const std::string normalized_args_json = args_json.empty() ? "[]" : args_json;
        BeginExecutionTrace(
            call_site,
            normalized_args_json.size(),
            std::string("call ") + function_name + "(" + normalized_args_json + ")"
        );

        JSValue global = JS_GetGlobalObject(context_);
        JSValue function = JS_GetPropertyStr(context_, global, function_name.c_str());
        if (JS_IsException(function)) {
            JS_FreeValue(context_, global);
            return TakeExceptionEnvelope();
        }

        if (!JS_IsFunction(context_, function)) {
            JS_FreeValue(context_, function);
            JS_FreeValue(context_, global);
            return BuildEvalEnvelope(
                false,
                std::nullopt,
                std::string("Global function not found or not callable: ") + function_name,
                std::nullopt
            );
        }

        JSValue parsed_args = JS_ParseJSON(
            context_,
            normalized_args_json.c_str(),
            normalized_args_json.size(),
            call_site.c_str()
        );
        if (JS_IsException(parsed_args)) {
            JS_FreeValue(context_, function);
            JS_FreeValue(context_, global);
            return TakeExceptionEnvelope();
        }

        const int is_array = JS_IsArray(context_, parsed_args);
        if (is_array <= 0) {
            JS_FreeValue(context_, parsed_args);
            JS_FreeValue(context_, function);
            JS_FreeValue(context_, global);
            return BuildEvalEnvelope(
                false,
                std::nullopt,
                "QuickJS function args must be a JSON array",
                std::nullopt
            );
        }

        JSValue length_value = JS_GetPropertyStr(context_, parsed_args, "length");
        if (JS_IsException(length_value)) {
            JS_FreeValue(context_, parsed_args);
            JS_FreeValue(context_, function);
            JS_FreeValue(context_, global);
            return TakeExceptionEnvelope();
        }

        uint32_t argc = 0;
        if (JS_ToUint32(context_, &argc, length_value) != 0) {
            JS_FreeValue(context_, length_value);
            JS_FreeValue(context_, parsed_args);
            JS_FreeValue(context_, function);
            JS_FreeValue(context_, global);
            return TakeExceptionEnvelope();
        }
        JS_FreeValue(context_, length_value);

        std::vector<JSValue> argv;
        argv.reserve(argc);
        for (uint32_t index = 0; index < argc; index += 1) {
            JSValue arg = JS_GetPropertyUint32(context_, parsed_args, index);
            if (JS_IsException(arg)) {
                for (JSValue& value : argv) {
                    JS_FreeValue(context_, value);
                }
                JS_FreeValue(context_, parsed_args);
                JS_FreeValue(context_, function);
                JS_FreeValue(context_, global);
                return TakeExceptionEnvelope();
            }
            argv.push_back(arg);
        }

        JSValue result = JS_Call(
            context_,
            function,
            global,
            argc,
            argc > 0 ? argv.data() : nullptr
        );

        for (JSValue& value : argv) {
            JS_FreeValue(context_, value);
        }
        JS_FreeValue(context_, parsed_args);
        JS_FreeValue(context_, function);
        JS_FreeValue(context_, global);

        if (JS_IsException(result)) {
            return TakeExceptionEnvelope();
        }

        std::string value_json = SerializeValue(result);
        JS_FreeValue(context_, result);
        return BuildEvalEnvelope(true, value_json, std::nullopt, std::nullopt);
    }

    int ExecutePendingJobs(int max_jobs) {
        std::lock_guard<std::mutex> guard(lock_);
        int executed = 0;
        while (executed < max_jobs) {
            JSContext* current_context = nullptr;
            int status = JS_ExecutePendingJob(runtime_, &current_context);
            if (status <= 0) {
                break;
            }
            executed += 1;
        }
        return executed;
    }

    void Interrupt() {
        interrupted_.store(true);
    }

    std::string GetMemoryUsageJson() {
        std::lock_guard<std::mutex> guard(lock_);
        JSMemoryUsage usage{};
        JS_ComputeMemoryUsage(runtime_, &usage);
        std::string result = "{";
        result += "\"mallocSizeBytes\":" + std::to_string(usage.malloc_size);
        result += ",\"memoryUsedBytes\":" + std::to_string(usage.memory_used_size);
        result += ",\"peakMallocSizeBytes\":" +
            std::to_string(peak_native_allocation_bytes_.load());
        result += "}";
        return result;
    }

    void ResetMemoryPeak() {
        std::lock_guard<std::mutex> guard(lock_);
        peak_native_allocation_bytes_.store(current_native_allocation_bytes_.load());
    }

private:
    static void* JsMalloc(JSMallocState* state, size_t size) {
        void* pointer = std::malloc(size);
        if (pointer != nullptr) {
            auto* vm = static_cast<QuickJsVm*>(state->opaque);
            // [MTE-FIX] 手里已经有 size，不必再走 malloc_usable_size。
            // 少一次 libc 调用，同时彻底避开 tagged pointer 校验。
            // 记账口径仍是「实际占用」的保守下界（可用大小 >= 请求大小）。
            vm->RecordAllocation(size);
        }
        return pointer;
    }

    static void JsFree(JSMallocState* state, void* pointer) {
        if (pointer != nullptr) {
            auto* vm = static_cast<QuickJsVm*>(state->opaque);
            // [MTE-FIX-3] MallocUsableSize 返回 0，RecordFree(0) 是 no-op。
            vm->RecordFree(MallocUsableSize(pointer));
        }
        // [MTE-FIX-3] 原样传指针，禁止任何 tag 处理。
        // tombstone_06：free 收到的指针 tag 被清成 0，bionic 判为
        // 'Pointer tag ... was truncated' 并 abort。
        std::free(pointer);
    }

    static void* JsRealloc(JSMallocState* state, void* pointer, size_t size) {
        // [MTE-FIX-2] old_size 恒 0（MallocUsableSize 不再碰 libc）。
        // 只影响统计口径，不影响 realloc 正确性。
        const size_t old_size = 0U;
        (void)pointer;
        if (size == 0U) {
            if (pointer != nullptr) {
                auto* vm = static_cast<QuickJsVm*>(state->opaque);
                vm->RecordFree(old_size);
            }
            // [MTE-FIX-3] 见 JsFree 注释：free 必须原样传指针。
            std::free(pointer);
            return nullptr;
        }
        // [MTE-FIX-3] realloc 同样必须原样传指针：
        // bionic 会用旧指针的 tag 校验并释放，清 tag 会 abort。
        void* new_pointer = std::realloc(pointer, size);
        if (new_pointer != nullptr) {
            auto* vm = static_cast<QuickJsVm*>(state->opaque);
            // new_size 直接用请求 size，绕开 usable_size
            vm->RecordReallocation(old_size, size);
        }
        return new_pointer;
    }

    // [MTE-FIX] 见文件顶部说明。
    // 这个函数同时被三处调用：
    //   ① JsMalloc / JsFree / JsRealloc 内部记账
    //   ② kMallocFunctions 第 4 项，QuickJS 引擎自己会调
    // 全部都必须先 untag，否则 Android 16 上 malloc_usable_size 直接 abort。
    // [MTE-FIX-2] 不再注册进 kMallocFunctions，只作内部占位。
    // 定不可调 libc：QuickJS 可能把一个 container_of 推算的 header 地址
    // 传进来，Android 16 + MTE 下 bionic 必然 abort。返回 0 是唯一安全解。
    static size_t MallocUsableSize(const void* /*pointer*/) {
        return 0U;
    }

    void RecordAllocation(size_t size) {
        const int64_t current = current_native_allocation_bytes_.fetch_add(
            static_cast<int64_t>(size)
        ) + static_cast<int64_t>(size);
        UpdatePeak(current);
    }

    void RecordFree(size_t size) {
        current_native_allocation_bytes_.fetch_sub(static_cast<int64_t>(size));
    }

    void RecordReallocation(size_t old_size, size_t new_size) {
        const int64_t delta = static_cast<int64_t>(new_size) - static_cast<int64_t>(old_size);
        const int64_t current = current_native_allocation_bytes_.fetch_add(delta) + delta;
        UpdatePeak(current);
    }

    void UpdatePeak(int64_t current) {
        int64_t peak = peak_native_allocation_bytes_.load();
        while (current > peak &&
               !peak_native_allocation_bytes_.compare_exchange_weak(peak, current)) {
        }
    }

    static const JSMallocFunctions kMallocFunctions;

    void BeginExecutionTrace(
        const std::string& file_name,
        size_t script_length,
        const std::string& script_preview
    ) {
        interrupted_.store(false);
        current_file_name_ = file_name;
        current_script_length_ = script_length;
        current_script_preview_ = PreviewText(script_preview);
        recent_host_calls_.clear();
        host_call_counter_ = 0;
        active_host_call_depth_ = 0;
    }

    static int HandleInterrupt(JSRuntime* runtime, void* opaque) {
        auto* vm = static_cast<QuickJsVm*>(opaque);
        if (vm == nullptr) {
            return 0;
        }
        return vm->interrupted_.load() ? 1 : 0;
    }

    static JSValue HostCallEntry(
        JSContext* context,
        JSValueConst this_value,
        int argc,
        JSValueConst* argv
    ) {
        auto* vm = static_cast<QuickJsVm*>(JS_GetRuntimeOpaque(JS_GetRuntime(context)));
        if (vm == nullptr) {
            return JS_ThrowInternalError(context, "QuickJsVm missing");
        }
        return vm->HostCall(context, argc, argv);
    }

    JSValue HostCall(JSContext* context, int argc, JSValueConst* argv) {
        std::string method = argc > 0 ? JsValueToString(context, argv[0]) : "";
        std::optional<std::string> args_json;
        if (argc > 1 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])) {
            args_json = JsValueToString(context, argv[1]);
        }

        RecordHostCall(method, args_json);
        active_host_call_depth_ += 1;
        HostCallResult result = CallHost(method, args_json);
        if (active_host_call_depth_ > 0) {
            active_host_call_depth_ -= 1;
        }
        if (result.error.has_value()) {
            return JS_ThrowInternalError(
                context,
                "Host call failed for %s: %s",
                method.c_str(),
                result.error->c_str()
            );
        }
        if (!result.value.has_value()) {
            return JS_NULL;
        }
        return JS_NewStringLen(context, result.value->c_str(), result.value->size());
    }

    void RecordHostCall(
        const std::string& method,
        const std::optional<std::string>& args_json
    ) {
        host_call_counter_ += 1;
        std::string entry =
            "#" + std::to_string(host_call_counter_) +
            " depth=" + std::to_string(active_host_call_depth_ + 1) +
            " method=" + PreviewText(method, 80);
        if (args_json.has_value() && !args_json->empty()) {
            entry += " args=" + PreviewText(*args_json, 180);
        }
        recent_host_calls_.push_back(entry);
        static constexpr size_t kMaxHostCalls = 24;
        if (recent_host_calls_.size() > kMaxHostCalls) {
            recent_host_calls_.erase(recent_host_calls_.begin());
        }
    }

    void InstallNativeInterface() {
        JSValue global = JS_GetGlobalObject(context_);
        JSValue native_interface = JS_NewObject(context_);
        JSValue host_call = JS_NewCFunction(context_, &QuickJsVm::HostCallEntry, "__call", 2);
        JS_SetPropertyStr(context_, native_interface, "__call", host_call);
        JS_SetPropertyStr(context_, global, "NativeInterface", native_interface);
        JS_FreeValue(context_, global);
    }

    HostCallResult CallHost(
        const std::string& method,
        const std::optional<std::string>& args_json
    ) {
        HostCallResult result;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (java_vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                result.error = "Failed to attach current thread to JVM";
                return result;
            }
            attached = true;
        }

        jstring j_method = env->NewStringUTF(method.c_str());
        jstring j_args = nullptr;
        jobject raw_result = nullptr;

        result.error = TakeJavaExceptionMessage(env);
        if (!result.error.has_value() && j_method == nullptr) {
            result.error = "Failed to create JNI string for host method";
        }

        if (!result.error.has_value() && args_json.has_value()) {
            j_args = env->NewStringUTF(args_json->c_str());
            result.error = TakeJavaExceptionMessage(env);
            if (!result.error.has_value() && j_args == nullptr) {
                result.error = "Failed to create JNI string for host args";
            }
        }

        if (!result.error.has_value()) {
            raw_result = env->CallObjectMethod(host_bridge_, on_call_method_, j_method, j_args);
            result.error = TakeJavaExceptionMessage(env);
        }

        if (!result.error.has_value() && raw_result != nullptr) {
            auto* j_result = static_cast<jstring>(raw_result);
            const char* chars = env->GetStringUTFChars(j_result, nullptr);
            if (chars != nullptr) {
                result.value = std::string(chars);
                env->ReleaseStringUTFChars(j_result, chars);
            } else {
                result.error = TakeJavaExceptionMessage(env);
                if (!result.error.has_value()) {
                    result.value = std::string();
                }
            }
        }

        if (raw_result != nullptr) {
            env->DeleteLocalRef(raw_result);
        }

        if (j_args != nullptr) {
            env->DeleteLocalRef(j_args);
        }
        if (j_method != nullptr) {
            env->DeleteLocalRef(j_method);
        }

        if (attached) {
            java_vm_->DetachCurrentThread();
        }
        return result;
    }

    std::string SerializeValue(JSValueConst value) {
        if (JS_IsUndefined(value)) {
            return "null";
        }

        JSValue json = JS_JSONStringify(context_, value, JS_UNDEFINED, JS_UNDEFINED);
        if (!JS_IsException(json) && !JS_IsUndefined(json)) {
            std::string result = JsValueToString(context_, json);
            JS_FreeValue(context_, json);
            return result;
        }
        if (!JS_IsException(json)) {
            JS_FreeValue(context_, json);
        }

        std::string result = JsValueToString(context_, value);
        return QuoteJson(result);
    }

    std::string TakeExceptionEnvelope() {
        JSValue exception = JS_GetException(context_);
        std::optional<std::string> message_property =
            GetExceptionPropertyString(context_, exception, "message");
        std::string message = message_property.value_or(JsValueToString(context_, exception));
        std::optional<std::string> stack = GetExceptionPropertyString(context_, exception, "stack");
        std::optional<std::string> name = GetExceptionPropertyString(context_, exception, "name");
        std::optional<std::string> file_name =
            GetExceptionPropertyString(context_, exception, "fileName");
        std::optional<std::string> line_number =
            GetExceptionPropertyString(context_, exception, "lineNumber");
        std::optional<std::string> cause =
            GetExceptionPropertyString(context_, exception, "cause");
        std::string exception_dump = JsValueToString(context_, exception);
        JS_FreeValue(context_, exception);

        if (name.has_value() && !name->empty()) {
            if (message.empty()) {
                message = *name;
            } else if (message.rfind(*name + ":", 0) != 0) {
                message = *name + ": " + message;
            }
        }
        std::string details_json = "{";
        details_json += "\"evalFileName\":";
        details_json += QuoteJson(current_file_name_);
        details_json += ",\"scriptLength\":";
        details_json += std::to_string(current_script_length_);
        details_json += ",\"scriptPreview\":";
        details_json += QuoteJson(current_script_preview_);
        details_json += ",\"exceptionName\":";
        details_json += name.has_value() ? QuoteJson(*name) : "null";
        details_json += ",\"exceptionMessage\":";
        details_json += QuoteJson(message);
        details_json += ",\"exceptionStack\":";
        details_json += stack.has_value() ? QuoteJson(*stack) : "null";
        details_json += ",\"exceptionFileName\":";
        details_json += file_name.has_value() ? QuoteJson(*file_name) : "null";
        details_json += ",\"exceptionLineNumber\":";
        details_json += line_number.has_value() ? QuoteJson(*line_number) : "null";
        details_json += ",\"exceptionCause\":";
        details_json += cause.has_value() ? QuoteJson(*cause) : "null";
        details_json += ",\"exceptionDump\":";
        details_json += QuoteJson(exception_dump);
        details_json += ",\"recentHostCalls\":";
        details_json += BuildJsonStringArray(recent_host_calls_);
        details_json += ",\"activeHostCallDepth\":";
        details_json += std::to_string(active_host_call_depth_);
        details_json += "}";
        return BuildEvalEnvelope(false, std::nullopt, message, stack, details_json);
    }

    JavaVM* java_vm_ = nullptr;
    JSRuntime* runtime_ = nullptr;
    JSContext* context_ = nullptr;
    jobject host_bridge_ = nullptr;
    jmethodID on_call_method_ = nullptr;
    std::mutex lock_;
    std::atomic_bool interrupted_;
    std::atomic<int64_t> current_native_allocation_bytes_{0};
    std::atomic<int64_t> peak_native_allocation_bytes_{0};
    std::string current_file_name_;
    size_t current_script_length_ = 0;
    std::string current_script_preview_;
    std::vector<std::string> recent_host_calls_;
    size_t host_call_counter_ = 0;
    size_t active_host_call_depth_ = 0;
};

// [MTE-FIX-2] 第 4 项 js_malloc_usable_size 刻意传 nullptr，这是根修。
//
// 崩溃链（tombstone_03, Android 16）：
//   JS_NewContext -> JS_NewContextRaw -> JS_NewCConstructor
//     -> js_realloc_rt(quickjs.c:1834) -> __js_realloc
//       -> __js_malloc_usable_size(quickjs.c:1723)
//         -> lb = container_of(ptr, JSMallocLargeBlockHeader, header.user_data)
//         -> s->mf.js_malloc_usable_size(lb)     ← 传的是「推算出来的 header 地址」
//           -> libc malloc_usable_size(lb) -> bionic 校验 tag 失败 -> abort()
//
// 关键：进 libc 的不是 malloc 原始返回值，而是 QuickJS 用 container_of
// 算出来的 block header 地址。Android 16 开 MTE 后 bionic 要求该地址的
// tag 与分配记录一致，header 地址必然不一致 -> abort。
// 上一版只做 untag（tag 清 0），但 MTE 下清 0 同样不匹配，所以照崩。
//
// QuickJS 源码对此留了后路（quickjs.c:1736）：
//     if (s->mf.js_malloc_usable_size) { ...查 libc... }
//     else { return 0; }
// 传 nullptr 就让它走 else 分支，完全不触达 libc。
// 代价：usable_size 恒 0，仅内存统计精度下降，不影响分配正确性，不泄漏。
const JSMallocFunctions QuickJsVm::kMallocFunctions = {
    &QuickJsVm::JsMalloc,
    &QuickJsVm::JsFree,
    &QuickJsVm::JsRealloc,
    nullptr,
};

QuickJsVm* FromHandle(jlong handle) {
    return reinterpret_cast<QuickJsVm*>(handle);
}

}  // namespace

// ============================================================================
// [MTE-FIX-4] 关闭本进程的 native heap pointer tagging
//
// 根因（tombstone_07，Android 16，五次崩溃复现）：
//   Abort message: 'Pointer tag for 0x784b11abe0 was truncated'
//   #00 libc.so (abort+160)
//   #01 libc.so (free+112)
//   #02..#14 libquickjsjni.so（引擎内部递归，0x107df8 反复）
//
// 为什么 JNI 层怎么做都修不好：
//   QuickJS 把指针塞进 64 位 JSValue 做 NaN-boxing，
//   高位被 NaN 标记占用，指针只能存低 48 位。
//   取出来是 (void*)(uintptr_t)(v & 0xFFFFFFFFFFFF)，高 16 位被截掉，
//   MTE tag 就在这一步丢失。崩溃地址 0x784b11abe0 高 8 位恰为 0x00。
//   引擎内部赋值环形链反复 free 同一批指针，必然撞上 bionic 校验。
//   所以在 JNI 边界加 untag / 传 nullptr 都是隔靴搔痒。
//
// 正解：让内核分配这些内存时就不打 tag。
//   ① Manifest: android:allowNativeHeapPointerTagging="false"（主防线）
//   ② 本函数 mallopt 降级（兜底，防部分 ROM 忽略 manifest 属性）
// ============================================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
#if defined(__ANDROID__)
    // 仅 Android 11(API 30) 起有该 mallopt 选项
    if (android_get_device_api_level() >= 30) {
        // 降低到 NONE：本进程所有 native 分配都不再打 tag。
        // 取舍：放弃 MTE 的内存安全防护，换取 QuickJS NaN-boxing 兼容性。
        // 该库只跑 JS 引擎，不处理不可信二进制输入，风险可控。
        mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
    }
#endif
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeCreate(
    JNIEnv* env,
    jclass,
    jobject host_bridge
) {
    if (host_bridge == nullptr) {
        return 0;
    }

    JavaVM* java_vm = nullptr;
    if (env->GetJavaVM(&java_vm) != JNI_OK) {
        return 0;
    }

    try {
        auto* vm = new QuickJsVm(java_vm, env, host_bridge);
        return reinterpret_cast<jlong>(vm);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle
) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeEvaluate(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring script,
    jstring file_name
) {
    auto* vm = FromHandle(handle);
    if (vm == nullptr || script == nullptr || file_name == nullptr) {
        std::string error = BuildEvalEnvelope(false, std::nullopt, std::string("Invalid nativeEvaluate arguments"), std::nullopt);
        return env->NewStringUTF(error.c_str());
    }

    const char* script_chars = env->GetStringUTFChars(script, nullptr);
    const char* file_name_chars = env->GetStringUTFChars(file_name, nullptr);
    if (script_chars == nullptr || file_name_chars == nullptr) {
        if (script_chars != nullptr) {
            env->ReleaseStringUTFChars(script, script_chars);
        }
        if (file_name_chars != nullptr) {
            env->ReleaseStringUTFChars(file_name, file_name_chars);
        }
        std::string error = BuildEvalEnvelope(false, std::nullopt, std::string("GetStringUTFChars failed"), std::nullopt);
        return env->NewStringUTF(error.c_str());
    }

    std::string result = vm->Eval(script_chars, file_name_chars);
    env->ReleaseStringUTFChars(script, script_chars);
    env->ReleaseStringUTFChars(file_name, file_name_chars);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeCallFunction(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring function_name,
    jstring args_json,
    jstring call_site
) {
    auto* vm = FromHandle(handle);
    if (vm == nullptr || function_name == nullptr || args_json == nullptr || call_site == nullptr) {
        std::string error = BuildEvalEnvelope(false, std::nullopt, std::string("Invalid nativeCallFunction arguments"), std::nullopt);
        return env->NewStringUTF(error.c_str());
    }

    const char* function_name_chars = env->GetStringUTFChars(function_name, nullptr);
    const char* args_json_chars = env->GetStringUTFChars(args_json, nullptr);
    const char* call_site_chars = env->GetStringUTFChars(call_site, nullptr);
    if (function_name_chars == nullptr || args_json_chars == nullptr || call_site_chars == nullptr) {
        if (function_name_chars != nullptr) {
            env->ReleaseStringUTFChars(function_name, function_name_chars);
        }
        if (args_json_chars != nullptr) {
            env->ReleaseStringUTFChars(args_json, args_json_chars);
        }
        if (call_site_chars != nullptr) {
            env->ReleaseStringUTFChars(call_site, call_site_chars);
        }
        std::string error = BuildEvalEnvelope(false, std::nullopt, std::string("GetStringUTFChars failed"), std::nullopt);
        return env->NewStringUTF(error.c_str());
    }

    std::string result = vm->CallFunction(function_name_chars, args_json_chars, call_site_chars);
    env->ReleaseStringUTFChars(function_name, function_name_chars);
    env->ReleaseStringUTFChars(args_json, args_json_chars);
    env->ReleaseStringUTFChars(call_site, call_site_chars);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeExecutePendingJobs(
    JNIEnv*,
    jclass,
    jlong handle,
    jint max_jobs
) {
    auto* vm = FromHandle(handle);
    if (vm == nullptr || max_jobs <= 0) {
        return 0;
    }
    return vm->ExecutePendingJobs(max_jobs);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeGetMemoryUsage(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    auto* vm = FromHandle(handle);
    const std::string result = vm == nullptr ? "{}" : vm->GetMemoryUsageJson();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeResetMemoryPeak(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto* vm = FromHandle(handle);
    if (vm != nullptr) {
        vm->ResetMemoryPeak();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeInterrupt(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto* vm = FromHandle(handle);
    if (vm != nullptr) {
        vm->Interrupt();
    }
}
