package com.soltini.app.apis

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * PublicApiRegistry
 *
 * Central repository of all 1,756+ public APIs across 51 categories
 * from the public-apis/public-apis catalog.
 */
class PublicApiRegistry private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PublicApiRegistry"
        private const val ASSET_CATALOG = "public_apis_catalog.json"

        @Volatile
        private var instance: PublicApiRegistry? = null

        fun getInstance(context: Context): PublicApiRegistry =
            instance ?: synchronized(this) {
                instance ?: PublicApiRegistry(context.applicationContext).also { instance = it }
            }
    }

    private val categoriesMap: MutableMap<String, MutableList<PublicApiEntry>> = mutableMapOf()
    private val allApisList: MutableList<PublicApiEntry> = mutableListOf()
    private var isLoaded: Boolean = false

    init {
        loadCatalog()
    }

    @Synchronized
    private fun loadCatalog() {
        if (isLoaded) return
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(ASSET_CATALOG)
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val rootJson = JSONObject(sb.toString())
            val keys = rootJson.keys()
            while (keys.hasNext()) {
                val categoryName = keys.next()
                val apiArray = rootJson.optJSONArray(categoryName) ?: JSONArray()
                val listForCat = mutableListOf<PublicApiEntry>()
                for (i in 0 until apiArray.length()) {
                    val item = apiArray.getJSONObject(i)
                    val entry = PublicApiEntry(
                        name = item.optString("name", "Unknown"),
                        link = item.optString("link", ""),
                        description = item.optString("description", ""),
                        auth = item.optString("auth", "No"),
                        https = item.optString("https", "Yes"),
                        cors = item.optString("cors", "Unknown"),
                        category = item.optString("category", categoryName)
                    )
                    listForCat.add(entry)
                    allApisList.add(entry)
                }
                categoriesMap[categoryName] = listForCat
            }
            isLoaded = true
            Log.d(TAG, "Successfully loaded ${allApisList.size} public APIs across ${categoriesMap.size} categories.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading public APIs catalog: ${e.message}", e)
        }
    }

    fun getTotalApisCount(): Int = allApisList.size

    fun getCategories(): List<JSONObject> {
        return categoriesMap.map { (catName, apis) ->
            JSONObject().apply {
                put("category", catName)
                put("count", apis.size)
            }
        }.sortedBy { it.optString("category") }
    }

    fun getApisByCategory(category: String): List<PublicApiEntry> {
        // Try exact match or case-insensitive match
        val matchedKey = categoriesMap.keys.firstOrNull { it.equals(category, ignoreCase = true) }
            ?: categoriesMap.keys.firstOrNull { it.contains(category, ignoreCase = true) }
        return if (matchedKey != null) categoriesMap[matchedKey] ?: emptyList() else emptyList()
    }

    fun searchApis(
        query: String,
        categoryFilter: String? = null,
        requireNoAuth: Boolean = false,
        limit: Int = 15
    ): List<PublicApiEntry> {
        val qTokens = query.lowercase().split(" ", "_", "-").filter { it.length > 1 }

        val pool: List<PublicApiEntry> = if (!categoryFilter.isNullOrBlank()) {
            getApisByCategory(categoryFilter)
        } else {
            allApisList
        }

        return pool.asSequence()
            .filter { entry ->
                if (requireNoAuth && entry.requiresAuth) false else true
            }
            .map { entry ->
                var score = 0
                val nameLower = entry.name.lowercase()
                val descLower = entry.description.lowercase()
                val catLower = entry.category.lowercase()

                for (token in qTokens) {
                    if (nameLower == token) score += 50
                    else if (nameLower.contains(token)) score += 20

                    if (catLower.contains(token)) score += 15

                    if (descLower.contains(token)) score += 5
                }
                Pair(entry, score)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Recommends the best APIs for a natural language user task.
     */
    fun recommendApisForTask(taskDescription: String): JSONObject {
        val taskLower = taskDescription.lowercase()

        // 1. Identify intent category keywords
        val matchedCategory = when {
            taskLower.contains("weather") || taskLower.contains("mausam") || taskLower.contains("temperature") || taskLower.contains("rain") -> "Weather"
            taskLower.contains("crypto") || taskLower.contains("bitcoin") || taskLower.contains("btc") || taskLower.contains("ethereum") || taskLower.contains("eth") -> "Cryptocurrency"
            taskLower.contains("currency") || taskLower.contains("exchange rate") || taskLower.contains("dollar") || taskLower.contains("rupee") || taskLower.contains("inr") -> "Currency Exchange"
            taskLower.contains("dictionary") || taskLower.contains("meaning") || taskLower.contains("word") || taskLower.contains("definition") -> "Dictionaries"
            taskLower.contains("animal") || taskLower.contains("cat") || taskLower.contains("dog") || taskLower.contains("bird") || taskLower.contains("fish") -> "Animals"
            taskLower.contains("music") || taskLower.contains("song") || taskLower.contains("lyrics") || taskLower.contains("spotify") -> "Music"
            taskLower.contains("news") || taskLower.contains("samachar") || taskLower.contains("khabar") || taskLower.contains("headline") -> "News"
            taskLower.contains("finance") || taskLower.contains("stock") || taskLower.contains("market") || taskLower.contains("share") -> "Finance"
            taskLower.contains("map") || taskLower.contains("location") || taskLower.contains("ip") || taskLower.contains("geocode") || taskLower.contains("address") -> "Geocoding"
            taskLower.contains("anime") || taskLower.contains("manga") -> "Anime"
            taskLower.contains("book") || taskLower.contains("kitab") || taskLower.contains("novel") -> "Books"
            taskLower.contains("game") || taskLower.contains("comic") -> "Games & Comics"
            taskLower.contains("health") || taskLower.contains("covid") || taskLower.contains("medicine") -> "Health"
            taskLower.contains("job") || taskLower.contains("career") || taskLower.contains("naukri") -> "Jobs"
            taskLower.contains("security") || taskLower.contains("malware") || taskLower.contains("password") -> "Security"
            taskLower.contains("science") || taskLower.contains("math") || taskLower.contains("space") || taskLower.contains("nasa") -> "Science & Math"
            taskLower.contains("food") || taskLower.contains("drink") || taskLower.contains("recipe") || taskLower.contains("cocktail") -> "Food & Drink"
            taskLower.contains("transport") || taskLower.contains("train") || taskLower.contains("flight") || taskLower.contains("metro") -> "Transportation"
            taskLower.contains("shopping") || taskLower.contains("ecommerce") || taskLower.contains("product") -> "Shopping"
            taskLower.contains("machine learning") || taskLower.contains("ai") -> "Machine Learning"
            else -> null
        }

        val searchResults = searchApis(
            query = taskDescription,
            categoryFilter = matchedCategory,
            requireNoAuth = false,
            limit = 6
        )

        val resultsArray = JSONArray()
        for (api in searchResults) {
            resultsArray.put(api.toJsonObject())
        }

        return JSONObject().apply {
            put("task", taskDescription)
            put("detected_category", matchedCategory ?: "General Search")
            put("total_matched", searchResults.size)
            put("recommended_apis", resultsArray)
            put("advice", if (searchResults.any { !it.requiresAuth })
                "Found zero-auth APIs that can be invoked directly without an API key!"
            else
                "Some APIs require an apiKey or OAuth token. Documentation links are provided.")
        }
    }
}
