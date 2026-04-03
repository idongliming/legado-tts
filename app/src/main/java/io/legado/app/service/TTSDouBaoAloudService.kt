package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * 豆包TTS朗读服务
 *
 * 架构：流水线预下载 + 状态机驱动单段串行播放
 * - downloadAndPlay(index)：下载当前段 → 准备播放器 → 同时启动预下载下一段
 * - STATE_ENDED：检查预下载是否就绪 → 无缝切换(playFromCache) 或 按需下载(downloadAndPlay)
 * - upPlayPos：80ms 轮询实际进度，消除字符高亮漂移
 * - removeUnUseCache：修复 previousMediaId 与 audioCacheList key 格式不匹配导致永不清理的 bug
 */
@SuppressLint("UnsafeOptInUsageError")
class TTSDouBaoAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        val dataSourceFactory = ByteArrayDataSourceFactory(audioCache)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
    private val tag = "TTSDouBaoService"
    private var speechRate: Int = AppConfig.speechRatePlay + 5

    // 下载当前段的协程
    private var downloadTask: Coroutine<*>? = null
    // 预下载下一段的协程（并行于播放，不阻塞）
    private var preloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private var isReloadAudio = 0

    private val doubaoFetch = DouBaoFetch()
    private val audioCache = HashMap<String, ByteArray>()
    private val audioCacheList = arrayListOf<String>()
    // Bug修复：用 lastPlayedFileName 替代 previousMediaId，与 audioCacheList 中的 key 格式保持一致
    private var lastPlayedFileName = ""

    private val cacheKey = "tts_doubao_cookie"
    private var doubaoCookie = ""
    private val silentBytes: ByteArray by lazy {
        resources.openRawResource(R.raw.silent_sound).readBytes()
    }

    private fun getSharedPrefValue(context: Context?, defaultValue: String = ""): String {
        if (context == null) return defaultValue
        val sp = context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
        return sp.getString(cacheKey, defaultValue) ?: defaultValue
    }

    override fun onCreate() {
        super.onCreate()
        doubaoCookie = getSharedPrefValue(this)
        Log.d(tag, "doubaoCookie= $doubaoCookie")
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        preloadTask?.cancel()
        exoPlayer.release()
        doubaoFetch.release()
        removeAllCache()
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!requestFocus()) return
        Log.i(tag, "play contentList.size=${contentList.size}")
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            downloadAndPlay(nowSpeak)
        }
    }

    override fun playStop() {
        downloadTask?.cancel()
        preloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    // ---- 核心流水线 ----

    /**
     * 下载 index 段落并启动播放，同时触发 index+1 预下载。
     * 消除旧版 nowSpeak==targetIndex 竞态：下载完成直接操作播放器，不再做条件判断。
     */
    private fun downloadAndPlay(index: Int) {
        if (doubaoCookie.isEmpty()) {
            pauseReadAloud(true)
            toastOnUi("Cookie 缺失，请先添加")
            return
        }
        downloadTask?.cancel()
        preloadTask?.cancel()
        downloadTask = execute {
            ensureActive()
            val content = contentList[index].let {
                if (paragraphStartPos > 0 && index == nowSpeak) it.substring(paragraphStartPos) else it
            }
            val fileName = md5SpeakFileName(content)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")

            if (!isCached(fileName)) {
                Log.d(tag, "下载 index=$index")
                runCatching {
                    getSpeakStream(speakText, fileName)
                }.onFailure {
                    Log.e(tag, "下载失败 index=$index", it)
                    AppLog.put("下载当前段失败: $it", it, true)
                    pauseReadAloud()
                    return@execute
                }
            } else {
                Log.d(tag, "命中缓存 index=$index")
            }

            withContext(Main) {
                val mediaItem = MediaItem.Builder()
                    .setUri("memory://media/$fileName".toUri())
                    .setMediaId(fileName)
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                if (!pause) exoPlayer.play()
                Log.d(tag, "准备播放 index=$index fileName=$fileName")
            }

            // 下一段预下载，不阻塞当前播放触发
            val nextIndex = index + 1
            if (nextIndex <= contentList.lastIndex) {
                preloadIndex(nextIndex)
            }
        }.onError {
            Log.e(tag, "downloadTask 异常", it)
        }
    }

    /**
     * 后台静默预下载指定段落，不影响当前播放。
     */
    private fun preloadIndex(index: Int) {
        preloadTask?.cancel()
        preloadTask = execute {
            ensureActive()
            val content = contentList[index]
            val fileName = md5SpeakFileName(content)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (!isCached(fileName)) {
                Log.d(tag, "预下载 index=$index")
                runCatching {
                    getSpeakStream(speakText, fileName)
                    Log.d(tag, "预下载完成 index=$index")
                }.onFailure {
                    Log.e(tag, "预下载失败 index=$index", it)
                }
            } else {
                Log.d(tag, "预下载命中缓存 index=$index")
            }
        }
    }

    private suspend fun getSpeakStream(speakText: String, fileName: String): String {
        if (speakText.isEmpty()) {
            cacheAudio(fileName, silentBytes)
            return "silent"
        }
        val audioFailureCallback = object : DouBaoFetch.AudioGenFailureCallback {
            override fun onFailure(error: Throwable, message: String) {
                Log.e(tag, "音频生成失败：$message", error)
                pauseReadAloud()
            }
        }
        return withContext(Dispatchers.IO) {
            ensureActive()
            val inputStream = doubaoFetch.genAudio(audioFailureCallback, doubaoCookie, speakText)
            ensureActive()
            cacheAudio(fileName, inputStream)
            "success"
        }
    }

    private fun md5SpeakFileName(content: String): String {
        return MD5Utils.md5Encode16(MD5Utils.md5Encode16("$speechRate|$content"))
    }

    // ---- 播控 ----

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    /**
     * 80ms 轮询音频实际进度更新字符高亮，替代等比插值，消除漂移。
     */
    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val duration = exoPlayer.duration
                    val position = exoPlayer.currentPosition
                    if (duration > 0 && nowSpeak < contentList.size) {
                        val textLength = contentList[nowSpeak].length
                        val charPos = (textLength.toLong() * position / duration).toInt()
                        if (pageIndex + 1 < textChapter.pageSize &&
                            readAloudNumber + charPos > textChapter.getReadLength(pageIndex + 1)
                        ) {
                            pageIndex++
                            ReadBook.moveToNextPage()
                        }
                        upTtsProgress(readAloudNumber + charPos + 1)
                    }
                }
                delay(80)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        preloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        speechRate = AppConfig.speechRatePlay + 5
        removeAllCache()
        downloadAndPlay(nowSpeak)
    }

    private fun reloadAudio() {
        removeAllCache()
        downloadTask?.cancel()
        preloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        downloadAndPlay(nowSpeak)
    }

    // ---- ExoPlayer 回调 ----

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {}
            Player.STATE_BUFFERING -> {}

            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                playErrorNo = 0
                isReloadAudio = 0
                val currentIndex = nowSpeak

                // 在 updateNextPos 重置 paragraphStartPos 之前记录本段 fileName，用于缓存清理
                val currentContent = contentList[currentIndex].let {
                    if (paragraphStartPos > 0) it.substring(paragraphStartPos) else it
                }
                lastPlayedFileName = md5SpeakFileName(currentContent)

                updateNextPos() // paragraphStartPos 在此重置为 0

                Log.d(tag, "播放完毕 index=$currentIndex → nowSpeak=$nowSpeak")
                if (currentIndex < contentList.lastIndex && !pause) {
                    removeUnUseCache()
                    // 若预下载已就绪则无缝切换，否则下载后播放
                    val nextFileName = md5SpeakFileName(contentList[nowSpeak])
                    if (isCached(nextFileName)) {
                        Log.d(tag, "预下载命中，无缝切换 nowSpeak=$nowSpeak")
                        playFromCache(nowSpeak, nextFileName)
                    } else {
                        Log.d(tag, "预下载未就绪，启动下载 nowSpeak=$nowSpeak")
                        downloadAndPlay(nowSpeak)
                    }
                }
            }
        }
    }

    /**
     * 从缓存无缝切换到下一段，并继续预下载后续段落。
     */
    private fun playFromCache(index: Int, fileName: String) {
        lifecycleScope.launch {
            val mediaItem = MediaItem.Builder()
                .setUri("memory://media/$fileName".toUri())
                .setMediaId(fileName)
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            Log.d(tag, "无缝播放 index=$index")
            val nextNext = index + 1
            if (nextNext <= contentList.lastIndex) {
                preloadIndex(nextNext)
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
            else -> {}
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        Log.e(tag, "播放错误: ${error.cause?.message}", error)
        Log.e(tag, "errorCode=${error.errorCode}, playErrorNo=$playErrorNo")
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误(${error.localizedMessage})", error)
            if (isReloadAudio == 0) {
                playErrorNo = 0
                isReloadAudio++
                Timer().schedule(2000) { reloadAudio() }
            } else {
                pauseReadAloud()
            }
        } else {
            // 单段模式下错误直接跳到下一段
            val currentIndex = nowSpeak
            updateNextPos()
            if (currentIndex < contentList.lastIndex && !pause) {
                downloadAndPlay(nowSpeak)
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSEdgeAloudService>(actionStr)
    }

    // ---- 缓存管理 ----

    private fun cacheAudio(key: String, inputStream: InputStream): Boolean {
        return try {
            audioCache[key] = inputStream.toByteArray()
            if (!audioCacheList.contains(key)) audioCacheList.add(key)
            Log.d(tag, "缓存成功: $key")
            true
        } catch (e: Exception) {
            Log.d(tag, "缓存失败: $key")
            false
        }
    }

    private fun cacheAudio(key: String, byteArray: ByteArray): Boolean {
        audioCache[key] = byteArray
        if (!audioCacheList.contains(key)) audioCacheList.add(key)
        return true
    }

    /**
     * 清理已播放段落之前的旧缓存。
     * Bug修复：原来用 previousMediaId（格式 "${index}_${fileName}"）在 audioCacheList（仅存 fileName）中查找，
     * 永远返回 -1，缓存从不清理，导致内存持续增长。
     * 现改用 lastPlayedFileName（与 audioCacheList key 格式一致）修复此 bug。
     */
    private fun removeUnUseCache() {
        if (lastPlayedFileName.isEmpty()) return
        val targetIdx = audioCacheList.indexOf(lastPlayedFileName)
        if (targetIdx <= 0) return
        val toRemove = audioCacheList.subList(0, targetIdx)
        toRemove.forEach { audioCache.remove(it) }
        toRemove.clear()
        Log.d(tag, "清理旧缓存，剩余 ${audioCacheList.size} 条")
    }

    private fun removeAllCache() {
        audioCache.clear()
        audioCacheList.clear()
        lastPlayedFileName = ""
    }

    private fun isCached(key: String) = audioCache.containsKey(key)

    private fun InputStream.toByteArray(): ByteArray {
        val output = ByteArrayOutputStream()
        this.use { input ->
            output.use { out ->
                input.copyTo(out)
            }
        }
        return output.toByteArray()
    }
}
