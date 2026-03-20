package com.hebrewcall.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class TranslationManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = HashMap<String, String>()

    suspend fun translate(text: String, fromLang: String, toLang: String): String {
        if (text.isBlank()) return ""
        val key = "$fromLang|$toLang|$text"
        cache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=$fromLang&tl=$toLang&dt=t&q=$encoded"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext text

                // Parse: [[['translated','original',...],...],...]
                val outer = JSONArray(body)
                val inner = outer.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until inner.length()) {
                    val chunk = inner.getJSONArray(i)
                    if (!chunk.isNull(0)) sb.append(chunk.getString(0))
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) cache[key] = result
                result.ifEmpty { text }
            } catch (e: Exception) {
                android.util.Log.e("TranslationManager", "Error", e)
                text
            }
        }
    }
}
