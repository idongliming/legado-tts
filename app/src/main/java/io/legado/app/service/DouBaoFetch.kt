package io.legado.app.service

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.min

/**
 * 豆包TTS音频合成类
 * 每次合成使用独立管道流局部变量，避免多次调用间的并发写污染
 */
class DouBaoFetch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val deviceId: String = generateDeviceId()
    private val webId: String = generateWebId()
    private val DEFAULT_VOICE = "taozi"
    private val DEFAULT_FORMAT = "aac"
    private val DEFAULT_PITCH = 0
    private val DEFAULT_RATE = 0

    private fun generateDeviceId(): String {
        val random = Random()
        val min = 7400000000000000000L
        val max = 7499999999999999999L
        return (min + (random.nextDouble() * (max - min)).toLong()).toString()
    }

    private fun generateWebId(): String {
        val random = Random()
        val min = 7400000000000000000L
        val max = 7499999999999999999L
        return (min + (random.nextDouble() * (max - min)).toLong()).toString()
    }

    fun removeSpecialCharacters(text: String?): String {
        if (text == null || text.isEmpty()) return ""
        val decodedText = URLDecoder.decode(text, StandardCharsets.UTF_8)
        val pattern = Pattern.compile("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】""'']")
        return pattern.matcher(decodedText).replaceAll("")
    }

    private fun buildWsUrl(speaker: String, format: String, speed: Int, pitch: Int): String {
        val speakerId = SPEAKERS.getOrDefault(speaker, speaker)
        val params = StringBuilder()
        params.append("speaker=").append(speakerId)
            .append("&format=").append(format)
            .append("&speech_rate=").append((speed * 100).toInt())
            .append("&pitch=").append((pitch * 100).toInt())
            .append("&version_code=20800")
            .append("&language=zh")
            .append("&device_platform=web")
            .append("&aid=497858")
            .append("&real_aid=497858")
            .append("&pkg_type=release_version")
            .append("&device_id=").append(deviceId)
            .append("&pc_version=2.50.6")
            .append("&web_id=").append(webId)
            .append("&tea_uuid=").append(webId)
            .append("&region=CN")
            .append("&sys_region=CN")
            .append("&samantha_web=1")
            .append("&use-olympus-account=1")
            .append("&web_tab_id=").append(UUID.randomUUID().toString())
        return WS_URL + "?" + params.toString()
    }

    interface AudioGenFailureCallback {
        fun onFailure(error: Throwable, message: String)
    }

    /**
     * 生成音频，返回音频输入流。
     * 管道流使用局部变量，每次调用完全独立，防止并发场景下交叉写入。
     * 缓冲区扩大至 64KB，减少 flush 阻塞。
     */
    fun genAudio(
        failureCallback: AudioGenFailureCallback,
        cookie: String,
        text: String,
        voice: String = DEFAULT_VOICE,
        rate: Int = DEFAULT_RATE,
        pitch: Int = DEFAULT_PITCH,
        format: String = DEFAULT_FORMAT
    ): InputStream {
        val cleanedText = removeSpecialCharacters(text)
        val wsUrl = buildWsUrl(voice, format, rate, pitch)
        Log.d(TAG, "WebSocket URL: $wsUrl")

        // 局部管道流：每次调用独立，彻底避免实例变量并发污染
        val audioOutputStream = PipedOutputStream()
        val audioInputStream = PipedInputStream(audioOutputStream, 65536) // 64KB 缓冲

        try {
            val request = Request.Builder()
                .url(wsUrl)
                .header("Accept-Language", "en,zh-CN;q=0.9,zh;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Origin", "https://www.doubao.com")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
                )
                .header("Cookie", cookie)
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    Log.d(TAG, "WebSocket连接成功")
                    try {
                        val textMsg = JSONObject()
                        textMsg.put("event", "text")
                        textMsg.put("text", cleanedText)
                        webSocket.send(textMsg.toString())

                        val finishMsg = JSONObject()
                        finishMsg.put("event", "finish")
                        webSocket.send(finishMsg.toString())
                        Log.d(TAG, "已发送文本和结束信号")
                    } catch (e: JSONException) {
                        Log.e(TAG, "构建JSON消息失败", e)
                        webSocket.close(1001, "JSON构建失败")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    try {
                        val data = JSONObject(text)
                        val event = data.optString("event", "")
                        when {
                            "open_success" == event -> Log.d(TAG, "连接握手成功")
                            "sentence_start" == event -> {
                                val readable = data.optJSONObject("sentence_start_result")
                                    ?.optString("readable_text", "") ?: ""
                                Log.d(TAG, "合成: ${readable.substring(0, min(readable.length, 50))}")
                            }
                            "error" == event -> {
                                val msg = data.optString("message", "未知错误")
                                Log.e(TAG, "合成错误: $msg")
                                webSocket.close(1001, msg)
                            }
                            data.optInt("code", 0) != 0 -> {
                                val msg = "错误码 ${data.optInt("code")}: ${data.optString("message", "未知错误")}"
                                Log.e(TAG, msg)
                                webSocket.close(1001, msg)
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "解析JSON消息失败", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    super.onMessage(webSocket, bytes)
                    try {
                        Log.d(TAG, "收到bytes: ${bytes.size}")
                        audioOutputStream.write(bytes.toByteArray())
                        audioOutputStream.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "写入音频数据失败", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosing(webSocket, code, reason)
                    Log.d(TAG, "WebSocket正在关闭: $code - $reason")
                    try {
                        audioOutputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "关闭音频输出流失败", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    Log.e(TAG, "WebSocket连接失败", t)
                    try {
                        audioOutputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "关闭音频输出流失败", e)
                    }
                    failureCallback.onFailure(t, "WebSocket连接失败")
                }
            }

            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.e(TAG, "生成音频失败", e)
            failureCallback.onFailure(e, "生成音频失败")
        }

        return audioInputStream
    }

    fun release() {
        // OkHttpClient 自行管理连接池，此处留空供外部调用
    }

    companion object {
        private const val TAG = "TTSDouBaoFetch"
        private const val WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/tts"

        private val SPEAKERS: HashMap<String?, String?> = object : HashMap<String?, String?>() {
            init {
                put("taozi", "zh_female_taozi_conversation_v4_wvae_bigtts")
                put("shuangkuai", "zh_female_shuangkuai_emo_v3_wvae_bigtts")
                put("tianmei", "zh_female_tianmei_conversation_v4_wvae_bigtts")
                put("qingche", "zh_female_qingche_moon_bigtts")
                put("yangguang", "zh_male_yangguang_conversation_v4_wvae_bigtts")
                put("chenwen", "zh_male_chenwen_moon_bigtts")
                put("rap", "zh_male_rap_mars_bigtts")
                put("en_female", "en_female_sarah_conversation_bigtts")
                put("en_male", "en_male_adam_conversation_bigtts")
            }
        }
    }
}