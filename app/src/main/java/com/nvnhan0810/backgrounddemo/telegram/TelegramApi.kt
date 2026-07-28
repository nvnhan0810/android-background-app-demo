package com.nvnhan0810.backgrounddemo.telegram

import com.nvnhan0810.backgrounddemo.LearningLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Gọi Bot API bằng HttpURLConnection.
 * Base: https://api.telegram.org/bot<token>/METHOD
 *
 * HTTP 401 = token sai / hết hạn / dính ký tự lạ khi paste.
 */
object TelegramApi {

    class HttpException(
        val httpCode: Int,
        message: String
    ) : Exception(message)

    data class TgUpdate(
        val updateId: Long,
        val kind: Kind,
        val chatId: Long,
        val messageId: Long,
        val text: String,
        val fromName: String
    ) {
        enum class Kind { MESSAGE, EDITED_MESSAGE }
    }

    data class BotInfo(
        val id: Long,
        val username: String,
        val firstName: String
    )

    /** Kiểm tra token trước khi long-poll. */
    fun getMe(token: String): BotInfo {
        val body = get(token, "getMe")
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) {
            throw IllegalStateException(root.optString("description", "getMe failed"))
        }
        val result = root.getJSONObject("result")
        val info = BotInfo(
            id = result.optLong("id"),
            username = result.optString("username"),
            firstName = result.optString("first_name")
        )
        LearningLog.i(TAG, "getMe OK @${info.username} id=${info.id}")
        return info
    }

    fun deleteWebhook(token: String): Boolean {
        return try {
            val body = get(token, "deleteWebhook?drop_pending_updates=false")
            val ok = JSONObject(body).optBoolean("ok", false)
            LearningLog.i(TAG, "deleteWebhook ok=$ok")
            ok
        } catch (t: Throwable) {
            LearningLog.e(TAG, "deleteWebhook failed", t)
            false
        }
    }

    fun getUpdates(token: String, offset: Long, timeoutSec: Int = 25): List<TgUpdate> {
        val path = "getUpdates?offset=$offset&timeout=$timeoutSec&allowed_updates=" +
            URLEncoder.encode("""["message","edited_message"]""", "UTF-8")
        val body = get(token, path, readTimeoutMs = (timeoutSec + 15) * 1000)
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) {
            LearningLog.w(TAG, "getUpdates not ok: ${root.optString("description")}")
            return emptyList()
        }
        val arr: JSONArray = root.optJSONArray("result") ?: JSONArray()
        val out = mutableListOf<TgUpdate>()
        for (i in 0 until arr.length()) {
            parseUpdate(arr.getJSONObject(i))?.let { out += it }
        }
        return out
    }

    fun sendMessage(token: String, chatId: Long, text: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val path = "sendMessage?chat_id=$chatId&text=$encoded"
            val body = get(token, path)
            val ok = JSONObject(body).optBoolean("ok", false)
            if (!ok) {
                LearningLog.w(TAG, "sendMessage failed: ${JSONObject(body).optString("description")}")
            }
            ok
        } catch (t: Throwable) {
            LearningLog.e(TAG, "sendMessage failed chatId=$chatId", t)
            false
        }
    }

    private fun parseUpdate(obj: JSONObject): TgUpdate? {
        val updateId = obj.optLong("update_id", -1L)
        if (updateId < 0) return null

        val edited = obj.optJSONObject("edited_message")
        val message = obj.optJSONObject("message")
        val msgObj = edited ?: message ?: return null
        val kind = if (edited != null) TgUpdate.Kind.EDITED_MESSAGE else TgUpdate.Kind.MESSAGE

        val text = msgObj.optString("text", "")
        if (text.isBlank()) {
            LearningLog.d(TAG, "Skip non-text update_id=$updateId")
            return null
        }
        val chat = msgObj.optJSONObject("chat") ?: return null
        val chatId = chat.optLong("id", 0L)
        val messageId = msgObj.optLong("message_id", 0L)
        val from = msgObj.optJSONObject("from")
        val name = listOfNotNull(
            from?.optString("first_name")?.takeIf { it.isNotBlank() },
            from?.optString("last_name")?.takeIf { it.isNotBlank() },
            from?.optString("username")?.let { "@$it" }
        ).joinToString(" ").ifBlank {
            chat.optString("title").ifBlank { "chat:$chatId" }
        }

        return TgUpdate(
            updateId = updateId,
            kind = kind,
            chatId = chatId,
            messageId = messageId,
            text = text,
            fromName = name
        )
    }

    private fun get(token: String, methodAndQuery: String, readTimeoutMs: Int = 30_000): String {
        val clean = TelegramConfig.sanitizeToken(token)
        // Encode từng segment của path method; token giữ nguyên (Bot API chuẩn).
        val url = URL("https://api.telegram.org/bot$clean/$methodAndQuery")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            doInput = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            val text = if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                ""
            }
            if (code !in 200..299) {
                val hint = when (code) {
                    401 -> "Token sai hoặc hết hạn (HTTP 401). Paste lại từ BotFather, bấm Lưu token."
                    404 -> "Token/URL không tồn tại (HTTP 404)."
                    else -> "HTTP $code"
                }
                LearningLog.e(TAG, "$hint body=${text.take(200)}")
                throw HttpException(code, "$hint ${text.take(120)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private const val TAG = "TelegramApi"
}
