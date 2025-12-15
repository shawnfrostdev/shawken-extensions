package com.shawken.scloud

import com.lagradost.api.Log
import com.shawken.app.plugins.*
import com.shawken.app.utils.ExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class SCloudProvider : PluginProvider() {

    override var mainUrl = "https://scloudx.lol"
    override var name = "SCloudX"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDocument(mainUrl)
        val home = document.select("article.post, .movie-item, .content-item").take(20).mapNotNull { element ->
            parseSearchResult(element)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = fetchDocument(searchUrl)
        return document.select("article.post, .movie-item, .search-result").mapNotNull { element ->
            parseSearchResult(element)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url)
        
        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.split("-")?.firstOrNull()?.trim()
            ?: "Unknown"

        val yearRegex = """\\((\\d{4})\\)""".toRegex()
        val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = title.contains("Season", ignoreCase = true) || 
                      title.contains("S0", ignoreCase = true) ||
                      document.select("a[href*='episode']").isNotEmpty()

        val plot = extractPlot(document)
        val posterUrl = extractPosterUrl(document)
        val tags = extractTags(title)
        val rating = extractRating(document)

        return if (isSeries) {
            val episodes = parseEpisodes(document, url)
            newTvSeriesLoadResponse(cleanTitle(title), url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating
            }
        } else {
            newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data)

        // Look for iframe embeds
        document.select("iframe[src*='player'], iframe[src*='embed']").forEach { iframe ->
            val iframeSrc = iframe.attr("abs:src")
            if (iframeSrc.isNotEmpty()) {
                try {
                    val iframeDoc = fetchDocument(iframeSrc)
                    
                    // Look for video sources
                    iframeDoc.select("source[src], video source").forEach { source ->
                        val videoUrl = source.attr("abs:src")
                        if (videoUrl.isNotEmpty()) {
                            callback.invoke(ExtractorLink(
                                source = name,
                                name = "SCloudX - ${extractQualityFromUrl(videoUrl)}",
                                url = videoUrl,
                                referer = mainUrl,
                                quality = extractQualityValue(videoUrl),
                                isM3u8 = videoUrl.contains(".m3u8")
                            ))
                        }
                    }
                    
                    // Look for M3U8 in JavaScript
                    val scriptText = iframeDoc.select("script").joinToString("\\n") { it.html() }
                    extractM3u8FromScript(scriptText)?.let { m3u8Url ->
                        callback.invoke(ExtractorLink(
                            source = name,
                            name = "SCloudX - Auto",
                            url = m3u8Url,
                            referer = mainUrl,
                            quality = 1080,
                            isM3u8 = true
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("SCloudX", "Failed to load iframe: $iframeSrc", e)
                }
            }
        }

        // Look for direct video elements
        document.select("video source, source[src]").forEach { source ->
            val videoUrl = source.attr("abs:src")
            if (videoUrl.isNotEmpty()) {
                callback.invoke(ExtractorLink(
                    source = name,
                    name = "SCloudX - ${source.attr("data-quality") ?: "Auto"}",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = source.attr("data-quality")?.replace("p", "")?.toIntOrNull() ?: 1080,
                    isM3u8 = videoUrl.contains(".m3u8")
                ))
            }
        }

        return true
    }

    // Helper functions
    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response")
        return Jsoup.parse(html, url)
    }

    private fun parseSearchResult(element: Element): SearchResponse? {
        return try {
            val titleElement = element.selectFirst("h2 a, h3 a, .title a") ?: return null
            val title = titleElement.text().trim()
            val url = titleElement.attr("abs:href")
            
            val posterUrl = element.selectFirst("img")?.attr("abs:src")
                ?: element.selectFirst("img")?.attr("abs:data-src")

            val isSeries = title.contains("Season", ignoreCase = true) || 
                          title.contains("S0", ignoreCase = true)
            
            val type = when {
                isSeries -> TvType.TvSeries
                title.contains("Anime", ignoreCase = true) -> TvType.Anime
                else -> TvType.Movie
            }

            val quality = when {
                title.contains("4K", ignoreCase = true) || title.contains("2160p", ignoreCase = true) -> SearchQuality.FourK
                title.contains("1080p", ignoreCase = true) -> SearchQuality.HD
                title.contains("720p", ignoreCase = true) -> SearchQuality.HD
                else -> null
            }

            val yearRegex = """\\((\\d{4})\\)""".toRegex()
            val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

            newMovieSearchResponse(title, url, type) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        } catch (e: Exception) {
            Log.e("SCloudX", "Failed to parse search result", e)
            null
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeLinks = document.select("a[href*='episode'], .episode-link")
        
        episodeLinks.forEachIndexed { index, element ->
            val episodeUrl = element.attr("abs:href")
            val episodeText = element.text()
            
            val seasonEpisodeRegex = """S(\\d+)E(\\d+)""".toRegex(RegexOption.IGNORE_CASE)
            val match = seasonEpisodeRegex.find(episodeText)
            
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodeNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
            
            episodes.add(
                Episode(
                    data = episodeUrl,
                    name = episodeText.ifEmpty { "Episode $episodeNum" },
                    season = season,
                    episode = episodeNum
                )
            )
        }
        
        if (episodes.isEmpty()) {
            episodes.add(
                Episode(
                    data = baseUrl,
                    name = "Full Season",
                    season = 1,
                    episode = 1
                )
            )
        }
        
        return episodes
    }

    private fun extractPosterUrl(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.poster, img.wp-post-image")?.attr("abs:src")
            ?: document.selectFirst("article img")?.attr("abs:src")
    }

    private fun extractPlot(document: Document): String? {
        return document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".entry-content p, .description")?.text()
    }

    private fun extractTags(title: String): List<String> {
        val tags = mutableListOf<String>()
        
        if (title.contains("Dual Audio", ignoreCase = true)) tags.add("Dual Audio")
        if (title.contains("Hindi", ignoreCase = true)) tags.add("Hindi")
        if (title.contains("English", ignoreCase = true)) tags.add("English")
        if (title.contains("4K", ignoreCase = true)) tags.add("4K")
        if (title.contains("HDR", ignoreCase = true)) tags.add("HDR")
        if (title.contains("HEVC", ignoreCase = true)) tags.add("HEVC")
        if (title.contains("x265", ignoreCase = true)) tags.add("x265")
        if (title.contains("WEB-DL", ignoreCase = true)) tags.add("WEB-DL")
        if (title.contains("BluRay", ignoreCase = true)) tags.add("BluRay")
        
        return tags
    }
    
    private fun extractRating(document: Document): Int? {
        val ratingRegex = """IMDb[:\\s]+(\\d+\\.?\\d*)""".toRegex(RegexOption.IGNORE_CASE)
        val text = document.text()
        val match = ratingRegex.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace("Download", "", ignoreCase = true)
            .replace("Watch", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("2160", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> "2160p"
            url.contains("1080", ignoreCase = true) -> "1080p"
            url.contains("720", ignoreCase = true) -> "720p"
            url.contains("480", ignoreCase = true) -> "480p"
            else -> "Auto"
        }
    }
    
    private fun extractQualityValue(url: String): Int {
        return when {
            url.contains("2160", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> 2160
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("480", ignoreCase = true) -> 480
            else -> 1080
        }
    }
    
    private fun extractM3u8FromScript(script: String): String? {
        val patterns = listOf(
            """["']([^"']*\\.m3u8[^"']*)["']""".toRegex(),
            """file:\\s*["']([^"']*\\.m3u8[^"']*)["']""".toRegex(),
            """source:\\s*["']([^"']*\\.m3u8[^"']*)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(script)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
}
