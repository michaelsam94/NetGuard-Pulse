#include "session_shaper.h"

#include <android/log.h>
#include <jni.h>

namespace {

constexpr const char *kTag = "SessionShaperNative";

jclass g_result_class = nullptr;
jmethodID g_result_ctor = nullptr;

bool ensure_result_class(JNIEnv *env) {
    if (g_result_class != nullptr && g_result_ctor != nullptr) {
        return true;
    }
    jclass local = env->FindClass(
        "com/michael/netguardplus/system/hotspot/limit/SessionShaperNative$ShaperResult"
    );
    if (local == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "FindClass ShaperResult failed");
        return false;
    }
    g_result_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_result_ctor = env->GetMethodID(g_result_class, "<init>", "(ZJJ)V");
    if (g_result_ctor == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "GetMethodID ShaperResult ctor failed");
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_michael_netguardplus_system_hotspot_limit_SessionShaperNative_nativeCreate(
    JNIEnv *env,
    jobject,
    jlong rate_bytes_per_sec
) {
    ensure_result_class(env);
    return reinterpret_cast<jlong>(new SessionShaper(static_cast<int64_t>(rate_bytes_per_sec)));
}

JNIEXPORT void JNICALL
Java_com_michael_netguardplus_system_hotspot_limit_SessionShaperNative_nativeDestroy(
    JNIEnv *,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<SessionShaper *>(handle);
}

JNIEXPORT void JNICALL
Java_com_michael_netguardplus_system_hotspot_limit_SessionShaperNative_nativeSetRate(
    JNIEnv *,
    jobject,
    jlong handle,
    jlong rate_bytes_per_sec
) {
    if (handle != 0) {
        reinterpret_cast<SessionShaper *>(handle)->setRate(static_cast<int64_t>(rate_bytes_per_sec));
    }
}

JNIEXPORT jobject JNICALL
Java_com_michael_netguardplus_system_hotspot_limit_SessionShaperNative_nativeOnTraffic(
    JNIEnv *env,
    jobject,
    jlong handle,
    jlong bytes,
    jlong now_ns
) {
    if (handle == 0) {
        return nullptr;
    }
    if (!ensure_result_class(env)) {
        return nullptr;
    }
    const ShaperResult result =
        reinterpret_cast<SessionShaper *>(handle)->onTraffic(bytes, now_ns);

    return env->NewObject(
        g_result_class,
        g_result_ctor,
        static_cast<jboolean>(result.should_pause),
        static_cast<jlong>(result.pause_ms),
        static_cast<jlong>(result.debt_bytes)
    );
}

}
