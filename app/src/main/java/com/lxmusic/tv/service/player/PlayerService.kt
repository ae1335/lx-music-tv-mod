package com.lxmusic.tv.service.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.lxmusic.tv.data.cache.CacheManager
import com.lxmusic.tv.data.model.AudioQuality
import com.google.android.exoplayer2.upstream.TransferListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 音乐播放服务
 * 基于ExoPlayer的播放引擎
 * 前台服务（mediaPlayback）：退到后台/返回桌面时音乐继续播放
 */
class PlayerService : Service() {

    companion object {
        private const val TAG = "PlayerService"
        private const val CHANNEL_ID = "lx_music_playback"
        private const val NOTIFICATION_ID = 1001

        /**
         * 2.10 MediaSession 快进/快退步长（语音「快进十分钟」类指令的默认基准）
         */
        private const val FAST_FORWARD_MS = 10_000L
        private const val REWIND_MS = 10_000L

        /**
         * 2.10 通知按钮 / 媒体键 Intent action
         */
        private const val ACTION_PLAY_PAUSE = "com.lxmusic.tv.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.lxmusic.tv.action.NEXT"
        private const val ACTION_PREVIOUS = "com.lxmusic.tv.action.PREVIOUS"
        private const val ACTION_STOP = "com.lxmusic.tv.action.STOP"

        /**
         * 2.9 当前正在播放/写入的音频缓存 key（全局活跃 key）。
         * 缓存清理逻辑（CacheManager.cleanupIncompleteCache / cleanupOtherQualityCaches /
         * getCachedSongs 聚合淘汰）遇到该 key 必须强制跳过——否则会把正在边播边写的缓存物理删除，
         * 触发 CacheDataSource FLAG_IGNORE_CACHE_ON_ERROR 导致后续歌曲全部失去缓存能力。
         */
        @Volatile
        @JvmStatic
        var activePlayingCacheKey: String? = null
    }

    private var exoPlayer: ExoPlayer? = null
    private val binder = LocalBinder()
    private var stateListener: OnPlayerStateListener? = null

    /**
     * 2.10 MediaSession：让系统识别本应用为「媒体播放器」，
     * 华为小艺等语音助手与遥控器媒体键通过标准 MediaSession 协议下发控制指令。
     */
    private var mediaSession: MediaSession? = null

    /**
     * 2.10 队列控制回调：由 ViewModel 注入（播放队列逻辑在 VM 层，Service 不持有队列）。
     * MediaSession 的上一曲/下一曲指令经此转发到 VM 的 playPrevious()/playNext()。
     */
    interface QueueController {
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onTogglePlayPause()
    }

    @Volatile
    private var queueController: QueueController? = null

    /**
     * 2.10 当前播放曲目信息（供 MediaMetadata 使用）
     */
    private var currentTitle: String = "LX Music TV"
    private var currentArtist: String = "音乐播放中"
    private var currentAlbumArt: Bitmap? = null

    /**
     * 播放器内部Binder
     */
    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    /**
     * 简化的音乐信息（用于ViewModel传参）
     */
    data class MusicInfo(
        val id: String,
        val title: String,
        val artist: String,
        val url: String,
        val picUrl: String? = null,
        // 2.7 音频缓存 key（平台|歌曲id|音质）：缓存与 URL 解耦，
        // JS 源返回的 URL 变化时仍能命中本地缓存，不重新下载；为 null 时回退用 URL 做 key
        val cacheKey: String? = null
    )

    /**
     * 播放状态监听器接口
     */
    interface OnPlayerStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPositionChanged(position: Long, duration: Long)
        fun onPlaybackCompleted()
        // 2.8 实际播放音质（ExoPlayer 解析出的真实音频格式，映射后回调；播放页展示真实音质）
        fun onAudioFormatChanged(quality: AudioQuality)
        // 2.8 播放出错（坏 URL/网络错误等）：VM 据此清 URL 缓存并自动重试一次
        fun onPlaybackError()
    }

    /**
     * 2.8 将 ExoPlayer 解析出的真实音频格式映射为音质档位
     * @param codecs 编码器字符串（可能为 null；如 "mp3"/"flac"/"aac"）
     * @param mimeType 采样 MIME 类型（如 "audio/mpeg"/"audio/flac"/"audio/mp4"）
     * @param bitrate 比特率（bps，可能为 -1/0 未知）
     * @param sampleRate 采样率（Hz，可能为 -1/0 未知）
     */
    private fun mapFormatToQuality(
        codecs: String?,
        mimeType: String?,
        bitrate: Int,
        sampleRate: Int
    ): AudioQuality? {
        // codecs 可能为空（部分源未填充），用 sampleMimeType 兜底判断格式
        val fmt = (codecs ?: "").lowercase() + " " + (mimeType ?: "").lowercase()
        Log.d(TAG, "[mapFormat] codecs=$codecs mime=$mimeType bitrate=$bitrate sampleRate=$sampleRate fmt=$fmt")
        return when {
            "flac" in fmt ->
                if (sampleRate >= 96000) AudioQuality.FLAC_24BIT else AudioQuality.FLAC
            "mpeg" in fmt ->
                if (bitrate >= 320_000) AudioQuality.QUALITY_320K else AudioQuality.QUALITY_128K
            "aac" in fmt || "mp4a" in fmt || "mp4" in fmt ->
                if (bitrate >= 320_000) AudioQuality.QUALITY_320K else AudioQuality.QUALITY_128K
            else -> {
                Log.w(TAG, "[mapFormat] 未知音频格式: codecs=$codecs mime=$mimeType")
                null
            }
        }
    }

    private var currentPosition: Long = 0
    private var totalDuration: Long = 0
    // 2.7 当前播放歌曲的音频缓存 key（歌曲维度，与 URL 解耦；play() 时设置）
    @Volatile
    private var currentCacheKey: String? = null
    // 2.8 当前播放器的缓存模式（true=边播边缓存 SimpleCache，false=直连不缓存）；
    // play() 时对比开关，变化则重建播放器
    private var cacheModeEnabled = true
    // 2.8 当前歌曲是否完整播放完（STATE_ENDED = 听完，缓存完整可保留）；
    // 未听完就切换/停止/退出 → 删除半截缓存（removeAudioByKey）
    private var currentSongCompleted = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[lifecycle] onCreate")
        createNotificationChannel()
        // 2.10 顺序要求：MediaSession 必须在 startForegroundService() 之前创建，
        // 因为 MediaStyle 通知需要 sessionToken 才能构建；否则首次通知无法绑定会话。
        initMediaSession()
        startForegroundService()
        initPlayer()
    }

    /**
     * 2.10 初始化 MediaSession（系统媒体控件 / 语音助手 / 遥控器媒体键的接入点）。
     *
     * 使用 framework 原生 [MediaSession]（API 21+）而非 Media3：
     * 现有播放层基于 ExoPlayer 2.19.1，升级到 Media3 需重写整个播放器层，API 不兼容风险高；
     * framework API 已满足 minSdk 23，改动范围最小。
     */
    private fun initMediaSession() {
        try {
            val session = MediaSession(this, "LXMusicTVSession")
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "[MediaSession] onPlay")
                    if (isPlaying()) pause() else resume()
                    pushPlaybackState()
                }

                override fun onPause() {
                    Log.d(TAG, "[MediaSession] onPause")
                    pause()
                    pushPlaybackState()
                }

                override fun onStop() {
                    Log.d(TAG, "[MediaSession] onStop")
                    pause()
                    pushPlaybackState()
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "[MediaSession] onSkipToNext")
                    queueController?.onSkipToNext()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "[MediaSession] onSkipToPrevious")
                    queueController?.onSkipToPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "[MediaSession] onSeekTo pos=$pos")
                    seekTo(pos)
                    pushPlaybackState()
                }

                override fun onFastForward() {
                    seekTo((getCurrentPosition() + FAST_FORWARD_MS).coerceAtMost(getDuration()))
                    pushPlaybackState()
                }

                override fun onRewind() {
                    seekTo((getCurrentPosition() - REWIND_MS).coerceAtLeast(0L))
                    pushPlaybackState()
                }
            })

            // 会话活动：点击通知 / 语音「打开音乐」时回到播放页
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.setSessionActivity(pendingIntent)
            }
            mediaSession = session
            pushPlaybackState()
            Log.i(TAG, "[MediaSession] 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "[MediaSession] 初始化失败: ${e.message}")
        }
    }

    /**
     * 2.10 向系统同步播放状态（播放/暂停/进度/时长）。
     * 语音助手与遥控器媒体键均依赖此状态判断当前是播放还是暂停。
     */
    private fun pushPlaybackState() {
        val session = mediaSession ?: return
        try {
            val isPlaying = isPlaying()
            val position = getCurrentPosition()
            val duration = getDuration()
            // 2.10 状态映射：SYSTEM 只在用户主动停止时给 STOPPED，其余用 PLAYING/PAUSED 表达
            val state = if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                PlaybackState.STATE_STOPPED
            } else if (isPlaying) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_STOP
            val playbackState = PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position, 1.0f)
                .build()
            session.setPlaybackState(playbackState)
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "LX Music TV")
                    .apply {
                        if (duration > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                        currentAlbumArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                    }
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "[MediaSession] 同步状态失败: ${e.message}")
        }
    }

    /**
     * 2.10 设置队列控制回调（由 ViewModel 注入）
     */
    fun setQueueController(controller: QueueController?) {
        queueController = controller
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放控制通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 启动前台服务（媒体播放类型）
     */
    private fun startForegroundService() {
        try {
            val notification = buildNotification("LX Music TV", "音乐播放中")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    /**
     * 构建播放通知（2.10 改为 MediaStyle，带上一首/播放暂停/下一首操作按钮）。
     *
     * 关键点：MediaStyle 必须通过 setMediaSession(sessionToken) 绑定会话，
     * 否则系统无法把通知与 MediaSession 关联，语音助手/锁屏控件拿不到控制能力。
     */
    private fun buildNotification(title: String, content: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // 2.10 媒体操作按钮
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous,
                "上一首",
                servicePendingIntent(ACTION_PREVIOUS, 1)
            ).build()
        )
        if (isPlaying()) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "暂停",
                    servicePendingIntent(ACTION_PLAY_PAUSE, 2)
                ).build()
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_play,
                    "播放",
                    servicePendingIntent(ACTION_PLAY_PAUSE, 3)
                ).build()
            )
        }
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next,
                "下一首",
                servicePendingIntent(ACTION_NEXT, 4)
            ).build()
        )

        // 2.10 绑定 MediaSession（通知才能被识别为媒体播放通知）
        mediaSession?.let { session ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    /**
     * 构造指向本 Service 的 PendingIntent（通知按钮点击时转发到 Service 处理）
     */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 2.10 处理通知按钮/媒体键转发的 Intent 指令。
     * Service 被 exported=false，外部无法注入，仅本应用内部 PendingIntent 可达。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[lifecycle] onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (isPlaying()) pause() else resume()
                pushPlaybackState()
                updateNotification(currentTitle, currentArtist)
            }
            ACTION_NEXT -> queueController?.onSkipToNext()
            ACTION_PREVIOUS -> queueController?.onSkipToPrevious()
            ACTION_STOP -> {
                pause()
                pushPlaybackState()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "[lifecycle] onDestroy, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        // 2.10 释放 MediaSession：否则系统媒体控件会残留一个死会话，语音助手点「暂停」无响应
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            Log.e(TAG, "[MediaSession] 释放失败: ${e.message}")
        }
        releasePlayer()
        super.onDestroy()
    }

    /**
     * 初始化播放器
     */
    private fun initPlayer() {
        // 浏览器 UA：部分音源 URL API（如玉宁熙的 metingapi）带反盗链，
        // 非浏览器 UA（ExoPlayer/OkHttp 默认）直接返回 418 I'm a Teapot
        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", browserUserAgent)
                        .build()
                )
            }
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        // 包装 DataSource：渐进式首请求强制追加 Range: bytes=0-
        // 背景：部分 CDN（如酷我 car-er.kuwo.cn，长青音源的跳转目标）对不带 Range 的
        // 普通 GET 返回 0 字节导致播放器一直缓冲；PC 端浏览器/HTML5 播放器默认带 Range
        // 所以 PC 正常而本应用卡住。seek 请求由 ExoPlayer 自带 Range，不受影响。
        val dataSourceFactory = DataSource.Factory {
            RangeHeaderDataSource(okHttpDataSourceFactory.createDataSource())
        }

        // 2.7 播放缓存：CacheDataSource 包装网络源，播放时边播边写入 SimpleCache，
        // 同一首歌第二次播放直接读本地缓存——全程只下载一次，无需「播放完成后再下载」。
        // 缓存 key 用「歌曲维度」（由 play() 设置 currentCacheKey，见 MusicInfo.cacheKey），
        // 与 URL 解耦：JS 源返回的 URL 变化也能命中缓存，不重新下载。
        // 播放失败（URL 过期/网络错误）时忽略缓存走网络（FLAG_IGNORE_CACHE_ON_ERROR）。
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(CacheManager.getAudioCache(this))
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setCacheKeyFactory { dataSpec ->
                currentCacheKey ?: dataSpec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 2.8 音乐缓存开关（设置-缓存管理页，默认开启）：关闭时用直连数据源（边播边缓存关闭）
        val musicCacheEnabled = isMusicCacheEnabled()
        cacheModeEnabled = musicCacheEnabled
        val mediaSourceFactory = if (musicCacheEnabled) {
            DefaultMediaSourceFactory(cacheDataSourceFactory)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // 2.8 真实音频格式监听：2.19 起 Player.Listener 已无 onAudioInputFormatChanged，
                // 该回调位于 AnalyticsListener（2.x 全版本存在）；播放时解析出真实格式 → 映射音质 → 回调 VM
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: com.google.android.exoplayer2.Format
                    ) {
                        Log.d(TAG, "[音频格式] codecs=${format.codecs} mime=${format.sampleMimeType} bitrate=${format.bitrate} sampleRate=${format.sampleRate}")
                        val quality = mapFormatToQuality(
                            format.codecs,
                            format.sampleMimeType,
                            format.bitrate,
                            format.sampleRate
                        )
                        if (quality != null) {
                            Log.i(TAG, "实际音质: ${quality.displayName}")
                            stateListener?.onAudioFormatChanged(quality)
                        }
                    }
                })
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d(TAG, "播放器准备就绪")
                                totalDuration = duration
                                stateListener?.onPositionChanged(currentPosition, totalDuration)
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "播放结束")
                                // 2.8 完整播放完 → 标记缓存完整（停止/切歌时保留缓存）；
                                // 并持久化「完整缓存」标记：以后中途切走/退出也不会误删完整缓存
                                currentSongCompleted = true
                                markCacheCompleted(currentCacheKey)
                                stateListener?.onPlaybackCompleted()
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "缓冲中...")
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "播放器空闲")
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        stateListener?.onPlaybackStateChanged(isPlaying)
                        if (isPlaying) {
                            startPositionTracking()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "播放错误: ${error.message}, errorCode=${error.errorCodeName}")
                        // 2.8 播放出错（坏 URL/403/无效直链等）：清掉当前歌曲的 URL 缓存，
                        // 防止污染缓存被后续播放命中（换源后收藏页播放失败问题）；并回调 VM 自动重试
                        currentCacheKey?.let { CacheManager.removeUrl(it) }
                        stateListener?.onPlaybackError()
                    }
                })
            }
    }

    /**
     * 包装 DataSource：仅对首请求（position=0 且 length=UNSET）追加 Range: bytes=0-。
     * 后续 seek 请求（position>0 或 length 已定）由 ExoPlayer 自动生成正确的 Range 头，
     * 本包装直接透传，不影响拖动进度。
     */
    private class RangeHeaderDataSource(private val delegate: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val spec = if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                // 自定义头优先于自动 Range：初始请求自动 Range 为 null，此头会生效
                dataSpec.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
            } else {
                dataSpec
            }
            return delegate.open(spec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            delegate.close()
        }
    }

    /**
     * 开始位置追踪
     */
    private fun startPositionTracking() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        currentPosition = player.currentPosition
                        totalDuration = player.duration
                        stateListener?.onPositionChanged(currentPosition, totalDuration)
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * 2.8 音乐缓存开关（设置-缓存管理页，SharedPreferences，默认开启）
     */
    private fun isMusicCacheEnabled(): Boolean {
        return try {
            getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                .getBoolean("music_cache_enabled", true)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 播放音乐
     */
    fun play(musicInfo: MusicInfo) {
        Log.d(TAG, "[play] 收到播放请求: ${musicInfo.title}, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        try {
            // 播放前确保播放器已初始化（退出重建后 exoPlayer 可能为 null）
            if (exoPlayer == null) {
                Log.d(TAG, "[play] exoPlayer 为 null，重新 initPlayer()")
                initPlayer()
            }
            // 2.8 音乐缓存开关变化（设置-缓存管理页）时重建播放器：缓存/直连模式切换
            if (isMusicCacheEnabled() != cacheModeEnabled) {
                Log.i(TAG, "音乐缓存开关变化，重建播放器: cacheMode=$cacheModeEnabled → ${isMusicCacheEnabled()}")
                releasePlayer()
                initPlayer()
            }
            // 2.8 新歌曲：重置「完整播放」标记（播放中/播放完前停止都视为未完整）
            currentSongCompleted = false
            // 2.7 设置音频缓存 key（歌曲维度）：URL 变化也能命中本地缓存
            currentCacheKey = musicInfo.cacheKey
            // 2.9 全局活跃 key：供缓存清理逻辑免死保护（禁止删除正在写入的分片）
            activePlayingCacheKey = musicInfo.cacheKey
            // 2.9 显式绑定 MediaItem 的 customCacheKey：与管道 DataSpec 强绑定，
            // 杜绝切歌时序交叉污染（后台清理/聚合淘汰误伤新歌缓存）
            val mediaItem = MediaItem.Builder()
                .setUri(musicInfo.url)
                .setCustomCacheKey(musicInfo.cacheKey)
                .build()
            exoPlayer?.apply {
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            // 更新前台通知标题为当前歌曲
            currentTitle = musicInfo.title
            currentArtist = musicInfo.artist
            updateNotification(musicInfo.title, musicInfo.artist)
            // 2.10 同步媒体元数据（标题/歌手）给 MediaSession，供语音助手播报曲目信息
            pushPlaybackState()
            Log.d(TAG, "开始播放: ${musicInfo.title} - ${musicInfo.artist}, URL: ${musicInfo.url}")
        } catch (e: Exception) {
            Log.e(TAG, "播放失败: ${e.message}")
        }
    }

    /**
     * 停止播放并彻底关闭前台服务（退出应用时调用）。
     * 区别于 [stop]：额外释放播放器、移除前台通知、停止自身服务，
     * 避免退出后服务仍常驻导致重进应用播放异常。
     */
    fun stopAndShutdown() {
        Log.d(TAG, "[stopAndShutdown] 开始, exoPlayer=${if (exoPlayer != null) "非空" else "null"}")
        // 2.8 退出时当前歌曲若未完整播放：删除半截缓存（不留无用数据）
        discardIncompleteCache()
        try {
            exoPlayer?.stop()
        } catch (_: Exception) {}
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "[stopAndShutdown] 已释放播放器并移除前台通知，即将 stopSelf")
        stopSelf()
    }

    /**
     * 更新前台服务通知（当前歌曲信息）
     */
    private fun updateNotification(title: String, content: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(title, content))
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}")
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        exoPlayer?.pause()
        // 2.10 同步状态到 MediaSession 与媒体通知
        pushPlaybackState()
        updateNotification(currentTitle, currentArtist)
    }

    /**
     * 继续播放
     */
    fun resume() {
        exoPlayer?.play()
        // 2.10 同步状态到 MediaSession 与媒体通知
        pushPlaybackState()
        updateNotification(currentTitle, currentArtist)
    }

    /**
     * 2.8 重建播放器：释放旧实例并重新初始化。
     * 用途：① 音频缓存被清除后（旧 CacheDataSource 持有已释放的 SimpleCache）；
     *      ② 播放报错后换源重试（ExoPlayer 处于 ERROR 状态时直接 setMediaItem/prepare
     *         会立即再次失败，重建后从干净状态播放，后续源不再「秒失败」）。
     */
    fun rebuildPlayer() {
        try {
            releasePlayer()
            initPlayer()
            Log.i(TAG, "播放器已重建")
        } catch (e: Exception) {
            Log.e(TAG, "重建播放器失败: ${e.message}")
        }
    }

    /**
     * 2.8 音频缓存被清除后调用：重建播放器。
     * 旧 ExoPlayer 的 CacheDataSource 持有已 release、文件已删除的旧 SimpleCache，
     * 不重建会导致后续播放全部失败（重启后才恢复）——清除音频缓存后必须重建。
     */
    fun rebuildForCacheCleared() {
        rebuildPlayer()
    }

    /**
     * 停止播放（切歌/失败重试等场景）
     */
    fun stop() {
        // 2.8 未完整播放的歌曲：清除半截缓存（切换/停止时不保留无用数据；完整听完的保留）
        discardIncompleteCache()
        exoPlayer?.stop()
        // 2.10 同步停止状态到 MediaSession
        pushPlaybackState()
    }

    /**
     * 2.8 当前歌曲未完整缓存时，删除其音频缓存。
     * 判断规则：仅当「这次没听完」且「该歌从未完整缓存过」才删——
     * 以前完整听过（缓存整首在盘上）的歌，中途切走/退出不删，避免误删完整缓存。
     */
    private fun discardIncompleteCache() {
        val key = currentCacheKey
        if (!currentSongCompleted && key != null && !isCacheCompleted(key)) {
            Log.d(TAG, "歌曲缓存未完整（从未完整听过），清除: $key")
            CacheManager.removeAudioByKey(this, key)
        }
        currentSongCompleted = false
        currentCacheKey = null
        // 2.9 停止后不再有「正在写入」的缓存：解除活跃 key 免死保护，
        // 后续清理/聚合淘汰可正常回收该歌曲的陈旧分片
        activePlayingCacheKey = null
    }

    /**
     * 2.8 该缓存 key 是否曾完整播放过（持久化标记，完整缓存应保留）
     */
    private fun isCacheCompleted(key: String): Boolean {
        return try {
            getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
                .getStringSet("music_cache_completed", emptySet())?.contains(key) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 2.8 记录「完整播放过」的缓存 key（持久化；上限 500 防无限增长，超出清空重建）
     */
    private fun markCacheCompleted(key: String?) {
        if (key.isNullOrBlank()) return
        try {
            val prefs = getSharedPreferences("lx_settings", Context.MODE_PRIVATE)
            val set = (prefs.getStringSet("music_cache_completed", emptySet()) ?: emptySet()).toMutableSet()
            if (set.size >= 500) set.clear()
            set.add(key)
            prefs.edit().putStringSet("music_cache_completed", set).apply()
        } catch (e: Exception) {
        }
    }

    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    /**
     * 获取当前播放位置
     */
    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    /**
     * 获取总时长
     */
    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0
    }

    /**
     * 获取播放状态
     */
    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    /**
     * 设置播放状态监听器
     */
    fun setOnPlayerStateListener(listener: OnPlayerStateListener?) {
        this.stateListener = listener
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    /**
     * 释放播放器
     */
    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
