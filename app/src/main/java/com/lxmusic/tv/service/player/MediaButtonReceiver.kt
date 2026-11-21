package com.lxmusic.tv.service.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * 2.10 媒体键接收器（MediaButtonReceiver）。
 *
 * Android TV 遥控器上的「播放 / 暂停 / 上一曲 / 下一曲」等按键会以
 * ACTION_MEDIA_BUTTON 广播形式送达 MediaButtonReceiver；系统会优先把
 * MediaSession 设为 active，从而自动路由到 MediaSession.Callback。
 *
 * 这里保留该 Receiver 作为兜底：当 MediaSession 尚未激活（例如应用刚启动、
 * 还没有开始播放）时，媒体键仍有落点，不会出现「按了没反应」。
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        Log.d(TAG, "[MediaButton] keyCode=${event.keyCode}")
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                requestAudioFocus(context)
                sendCommand(context, ACTION_PLAY_PAUSE)
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> sendCommand(context, ACTION_PLAY_PAUSE)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> sendCommand(context, ACTION_PLAY_PAUSE)
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                sendCommand(context, ACTION_NEXT)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND ->
                sendCommand(context, ACTION_PREVIOUS)
            KeyEvent.KEYCODE_MEDIA_STOP -> sendCommand(context, ACTION_STOP)
        }
    }

    /**
     * 请求音频焦点（媒体播放需要，避免被其他应用抢占）
     */
    private fun requestAudioFocus(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        } catch (e: Exception) {
            Log.e(TAG, "[MediaButton] 请求音频焦点失败: ${e.message}")
        }
    }

    /**
     * 把媒体键意图转发给 PlayerService（Service 内部处理并同步 MediaSession/通知）
     */
    private fun sendCommand(context: Context, action: String) {
        try {
            val intent = Intent(context, PlayerService::class.java).apply { this.action = action }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "[MediaButton] 转发指令失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
        private const val ACTION_PLAY_PAUSE = "com.lxmusic.tv.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.lxmusic.tv.action.NEXT"
        private const val ACTION_PREVIOUS = "com.lxmusic.tv.action.PREVIOUS"
        private const val ACTION_STOP = "com.lxmusic.tv.action.STOP"
    }
}
