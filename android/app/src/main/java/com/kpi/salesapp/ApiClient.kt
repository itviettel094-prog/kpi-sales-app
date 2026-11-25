package com.kpi.salesapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun call(
        execUrl: String,
        action: String,
        idToken: String?,
        params: JSONObject = JSONObject(),
        maxRetries: Int = 3
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val body = JSONObject()
            body.put("action", action)
            body.put("idToken", idToken ?: "")
            body.put("params", params)

            val request = Request.Builder()
                .url(execUrl)
                .post(body.toString().toRequestBody(JSON))
                .build()

            var lastError: Exception? = null
            for (attempt in 1..maxRetries) {
                try {
                    client.newCall(request).execute().use { resp ->
                        val text = resp.body?.string() ?: "{}"
                        return@withContext try {
                            JSONObject(text)
                        } catch (e: Exception) {
                            JSONObject().put("ok", false).put("error", "Phản hồi không hợp lệ từ server: $text")
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < maxRetries) {
                        delay(1000L * attempt)
                    }
                }
            }
            JSONObject().put("ok", false).put("error", "Không kết nối được server sau $maxRetries lần thử: ${lastError?.message}")
        }
    }
}
