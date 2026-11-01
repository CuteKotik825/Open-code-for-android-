package com.pocketcode.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * POST JSON с ответом в формате SSE. [onData] получает содержимое каждой строки `data:`
     * (вызывается в фоновом потоке). При отмене корутины соединение обрывается сразу.
     */
    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        onData: (String) -> Unit,
    ) = coroutineScope {
        RateGate.acquire()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        val reader = async(Dispatchers.IO) {
            call.execute().use { r ->
                if (!r.isSuccessful) {
                    throw IOException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(800)}")
                }
                val source = r.body?.source() ?: throw IOException("Пустой ответ сервера")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isNotEmpty()) onData(data)
                }
            }
        }
        try {
            reader.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    /** POST JSON; при отмене корутины запрос обрывается. */
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String {
        RateGate.acquire()
        return postJsonRaw(url, headers, body)
    }

    private suspend fun postJsonRaw(url: String, headers: Map<String, String>, body: String): String =
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
