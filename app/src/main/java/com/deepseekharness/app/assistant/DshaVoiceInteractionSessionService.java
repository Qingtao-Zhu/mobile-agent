package com.deepseekharness.app.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * DSHA 数字助理会话服务（V1）。
 *
 * <p>当用户触发系统 Assistant 手势/按键时，系统会回调
 * {@link #onNewSession(Bundle)} 创建一个会话。会话展示后立刻把现有的
 * {@code com.deepseekharness.app.ui.MainActivity} 以前台方式拉起，并带上
 * {@code open_launch=true}，让 MainActivity 直接切到 {@code nav_launch}，
 * 从而复用现有的 LaunchFragment / HarnessController / HarnessService 启动链路。
 *
 * <p>V1 的边界：
 * <ul>
 *   <li>不新建独立 Assistant Activity；</li>
 *   <li>不复制 Harness 启动逻辑，只做一次 Intent 转发；</li>
 *   <li>不处理语音识别与屏幕上下文。</li>
 * </ul>
 */
public class DshaVoiceInteractionSessionService extends VoiceInteractionSessionService {

    /** 现有 DSHA 主 Activity 的全限定类名（namespace 下的 ui 包）。 */
    private static final String MAIN_ACTIVITY_CLASS = "com.deepseekharness.app.ui.MainActivity";

    /** 现有 MainActivity 已支持的外部 Intent 约定：切到 nav_launch。 */
    private static final String EXTRA_OPEN_LAUNCH = "open_launch";

    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new DshaVoiceInteractionSession(this);
    }

    /**
     * 内部静态 Session：只做「把现有 MainActivity 拉起来」这一件事，
     * 避免为 V1 额外增加文件，也不给 SessionService 引入多余状态。
     */
    static final class DshaVoiceInteractionSession extends VoiceInteractionSession {

        DshaVoiceInteractionSession(Context context) {
            super(context);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);

            // 指向现有 MainActivity：包名在运行时由 context 解析（com.dsh.client），
            // 类名保持源码 namespace 下的 com.deepseekharness.app.ui.MainActivity。
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(getContext(), MAIN_ACTIVITY_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(EXTRA_OPEN_LAUNCH, true);

            // 使用 VoiceInteractionSession 的官方入口唤起，交回系统前台栈；
            // 不新建 Assistant Activity。
            startVoiceActivity(intent);

            // DSHA 已被拉起，V1 不需要会话继续停留。
            finish();
        }
    }
}
