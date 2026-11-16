package com.kpi.salesapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gọi 1 action tới Apps Script.
 * body gửi lên luôn có dạng: { action, idToken, params }
 * Kết quả trả về là JSONObject thô từ server (tự parse field "ok", "error", "data"...).
 */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun call(execUrl: String, action: String, idToken: String?, params: JSONObject = JSONObject()): JSONObject {
        return withContext(Dispatchers.IO) {
            val body = JSONObject()
            body.put("action", action)
            body.put("idToken", idToken ?: "")
            body.put("params", params)

            val request = Request.Builder()
                .url(execUrl)
                .post(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", "Phản hồi không hợp lệ từ server: $text")
                }
            }
        }
    }
}
