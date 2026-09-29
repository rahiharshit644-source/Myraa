package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.model.SourceReliability
import com.soltini.app.reach.security.ReachSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

interface RssBackend : ReachBackend {
    suspend fun parseFeed(feedUrl: String, maxItems: Int = 10): List<ResearchSource>
}

/**
 * RssFeedBackend
 *
 * Native RSS 2.0 and Atom XML parser.
 */
class RssFeedBackend(
    private val httpClient: OkHttpClient
) : RssBackend {
    override val id = "rss_feed_parser"
    override val name = "RSS / Atom Feed Parser"
    override val description = "Parses standard RSS 2.0 and Atom feeds directly (Zero-config)"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        HealthStatus.READY to "RSS & Atom feed parser ready (Native XML engine)"
    }

    override suspend fun parseFeed(feedUrl: String, maxItems: Int): List<ResearchSource> = withContext(Dispatchers.IO) {
        val safeUrl = ReachSecurityManager.validateAndNormalizePublicHttpUrl(feedUrl)

        val req = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", "MyraAssistant-FeedReader/1.0")
            .header("Accept", "application/rss+xml, application/atom+xml, text/xml, */*")
            .build()

        val xml = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("RSS feed fetch failed with HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }

        parseXmlFeed(xml, safeUrl, maxItems)
    }

    private fun parseXmlFeed(xml: String, feedUrl: String, maxItems: Int): List<ResearchSource> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val results = mutableListOf<ResearchSource>()
        var eventType = parser.eventType

        var inItem = false
        var currentTitle = ""
        var currentLink = ""
        var currentDesc = ""
        var currentPubDate = ""

        while (eventType != XmlPullParser.END_DOCUMENT && results.size < maxItems) {
            val tagName = parser.name?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName == "item" || tagName == "entry") {
                        inItem = true
                        currentTitle = ""
                        currentLink = ""
                        currentDesc = ""
                        currentPubDate = ""
                    } else if (inItem) {
                        when (tagName) {
                            "title" -> currentTitle = parser.nextText().trim()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                currentLink = if (!href.isNullOrBlank()) href else parser.nextText().trim()
                            }
                            "description", "summary", "content" -> currentDesc = parser.nextText().trim()
                            "pubdate", "published", "updated" -> currentPubDate = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "item" || tagName == "entry") {
                        inItem = false
                        if (currentTitle.isNotBlank()) {
                            val cleanSnippet = currentDesc.replace(Regex("<[^>]*>"), "").take(400)
                            val cleanUrl = if (currentLink.isNotBlank()) {
                                try {
                                    ReachSecurityManager.validateAndNormalizePublicHttpUrl(currentLink)
                                } catch (_: Exception) {
                                    feedUrl
                                }
                            } else {
                                feedUrl
                            }

                            results.add(
                                ResearchSource(
                                    title = currentTitle,
                                    url = cleanUrl,
                                    platform = ResearchPlatform.RSS,
                                    publishedAt = currentPubDate.ifBlank { null },
                                    snippet = cleanSnippet,
                                    fullContent = "Title: $currentTitle\nDate: $currentPubDate\nLink: $cleanUrl\n\n$cleanSnippet",
                                    reliability = SourceReliability.PRIMARY_SOURCE,
                                    backendUsed = id
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return results
    }
}
