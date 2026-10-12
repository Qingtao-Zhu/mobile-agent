package com.deepseekharness.app.assistant;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

/**
 * 空壳语音识别服务（V1 修正件）。
 *
 * <p>Android 框架的 {@code VoiceInteractionServiceInfo} 在解析
 * {@code res/xml/voice_interaction_service.xml} 时，**把 recognitionService 当作必填项**：
 * 缺了就直接设 {@code mParseError = "No recognitionService specified"}，
 * 于是整个 VoiceInteractionService 被判为非法（系统既不绑定、也不让注册数字助理角色）。
 *
 * <p>所以这里提供一个**什么都不做**的识别服务，只为通过那一条 XML 校验：
 * <ul>
 *   <li>不接 {@link SpeechRecognizer}（仅引用其错误码常量）；</li>
 *   <li>不做任何语音识别、不申请录音、不返回任何识别结果；</li>
 *   <li>被调用时立刻回 {@code ERROR_CLIENT}，不让调用方悬挂等待。</li>
 * </ul>
 *
 * <p>这不改变 V1 的边界：V1 依然「不实现语音识别链路」，只是把框架要求的占位声明补上。
 */
public class DshaRecognitionService extends RecognitionService {

    private static final String TAG = "DshaRecognition";

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        Log.i(TAG, "onStartListening: V1 不实现语音识别，直接回错误");
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException error) {
            Log.w(TAG, "回报错误失败", error);
        }
    }

    @Override
    protected void onCancel(Callback listener) {
        Log.i(TAG, "onCancel");
    }

    @Override
    protected void onStopListening(Callback listener) {
        Log.i(TAG, "onStopListening");
    }
}
