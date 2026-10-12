package com.deepseekharness.app.assistant;

import android.service.voice.VoiceInteractionService;

/**
 * DSHA 数字助理入口（V1）。
 *
 * <p>这是 Android 系统「默认数字助理」的顶层绑定组件。系统在把 DSHA 选为默认助理后，
 * 会绑定这个 {@link VoiceInteractionService}。
 *
 * <p>V1 刻意保持极轻量，遵守以下边界：
 * <ul>
 *   <li>不启动 Harness、不启动 Web、不做任何耗时初始化；</li>
 *   <li>不申请、不读取屏幕 Assist 上下文（supportsAssist=false）；</li>
 *   <li>不实现热词与语音识别链路。</li>
 * </ul>
 *
 * <p>真正「把现有 DSHA 启动流程拉起来」的逻辑位于同包的
 * {@link DshaVoiceInteractionSessionService}，本类只负责让系统完成绑定/识别。
 * 这是唯一新增的一层 Android Voice Interaction Adapter，不触碰 Harness 核心，
 * 也不复制任何 Harness 启动逻辑。
 */
public class DshaVoiceInteractionService extends VoiceInteractionService {
}
