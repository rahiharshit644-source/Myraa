package com.soltini.app.apis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * PublicApiExecutor
 *
 * Executes HTTP requests against public APIs, returning structured live data.
 * Includes instant zero-auth query handlers for common needs:
 * - Cryptocurrency prices (CoinGecko)
 * - Weather (Open-Meteo & wttr.in)
 * - Dictionary word meanings (Free Dictionary API)
 * - Currency exchange rates (Frankfurter)
 * - IP information & Geolocation (ip-api)
 * - Random jokes (JokeAPI)
 * - Facts & Advice (Advice Slip, Cat Facts, Dog Facts)
 * - General REST endpoints
 */
class PublicApiExecutor(private val context: Context) {

    companion object {
        private const val TAG = "PublicApiExecutor"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Executes an arbitrary public REST endpoint.
     */
    suspend fun executeApi(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            reqBuilder.addHeader("User-Agent", "MYRA-Personal-Assistant/2.0")

            if (method.equals("POST", ignoreCase = true)) {
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                reqBuilder.post((body ?: "{}").toRequestBody(mediaType))
            } else {
                reqBuilder.get()
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val respBody = response.body?.string() ?: ""
            val code = response.code

            val parsedJson: Any = try {
                if (respBody.trim().startsWith("{")) JSONObject(respBody)
                else if (respBody.trim().startsWith("[")) JSONArray(respBody)
                else respBody
            } catch (_: Exception) {
                respBody
            }

            JSONObject().apply {
                put("status", if (response.isSuccessful) "success" else "error")
                put("http_code", code)
                put("url", url)
                put("data", parsedJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing API call ($url): ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put("url", url)
                put("message", "API request failed: ${e.message}")
            }
        }
    }

    /**
     * Specialized zero-auth handlers for instant query resolution
     */

    suspend fun fetchCryptoPrice(cryptoName: String, vsCurrency: String = "usd"): JSONObject {
        val coin = cryptoName.trim().lowercase().let {
            when (it) {
                "btc" -> "bitcoin"
                "eth" -> "ethereum"
                "sol" -> "solana"
                "doge" -> "dogecoin"
                else -> it
            }
        }
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$coin&vs_currencies=${vsCurrency.lowercase()}&include_24hr_change=true"
        return executeApi(url)
    }

    suspend fun fetchWeather(city: String): JSONObject {
        val encodedCity = URLEncoder.encode(city.trim(), "UTF-8")
        val url = "https://wttr.in/$encodedCity?format=j1"
        return executeApi(url)
    }

    suspend fun lookupDictionaryWord(word: String): JSONObject {
        val encoded = URLEncoder.encode(word.trim(), "UTF-8")
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/$encoded"
        return executeApi(url)
    }

    suspend fun fetchCurrencyRate(from: String, to: String): JSONObject {
        val url = "https://api.frankfurter.app/latest?from=${from.trim().uppercase()}&to=${to.trim().uppercase()}"
        return executeApi(url)
    }

    suspend fun lookupIp(ip: String? = null): JSONObject {
        val url = if (ip.isNullOrBlank()) "http://ip-api.com/json" else "http://ip-api.com/json/${ip.trim()}"
        return executeApi(url)
    }

    suspend fun fetchRandomJoke(): JSONObject {
        val url = "https://v2.jokeapi.dev/joke/Any?safe-mode"
        return executeApi(url)
    }

    suspend fun fetchRandomAdvice(): JSONObject {
        val url = "https://api.adviceslip.com/advice"
        return executeApi(url)
    }

    suspend fun fetchCatFact(): JSONObject {
        val url = "https://catfact.ninja/fact"
        return executeApi(url)
    }

    suspend fun fetchDogFact(): JSONObject {
        val url = "https://dogapi.dog/api/v2/facts"
        return executeApi(url)
    }
}
