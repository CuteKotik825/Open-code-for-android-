package com.pocketcode.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** POST JSON; при отмене корутины запрос обрывается. */
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful) {
                            cont.resume(text)
                        } else {
                            cont.resumeWithException(IOException("HTTP ${r.code}: ${text.take(800)}"))
                        }
                    }
                }
            })
        }
}
