#include <jni.h>

#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_demeter_speech_core_WhisperBridge_nativeTranscribeChunk(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring /*path*/,
    jstring /*modelId*/) {
    return env->NewStringUTF("");
}
