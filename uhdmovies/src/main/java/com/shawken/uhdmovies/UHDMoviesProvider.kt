package com.shawken.uhdmovies

import com.lagradost.api.Log
import com.shawken.app.plugins.*
import com.shawken.app.utils.ExtractorLink
import com.shawken.app.utils.Qualities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class UHDMoviesProvider : PluginProvider() {

    override var mainUrl = "https://uhdmovies.stream"
    override var name = "UHDMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
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
        val home = document.select("article.post").take(20).mapNotNull { element ->
            parseSearchResult(element)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = fetchDocument(searchUrl)
        return document.select("article.post").mapNotNull { element ->
            parseSearchResult(element)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url)
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.split("-")?.firstOrNull()?.trim()
            ?: "Unknown"

        // Extract year from title
        val yearRegex = """\\((\\d{4})\\)""".toRegex()
        val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Determine if it's a TV series
        val isSeries = title.contains("Season", ignoreCase = true) || 
                      title.contains("S0", ignoreCase = true) ||
                      document.select("a[href*='season']").isNotEmpty()

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
                this.rating = rating?.toInt()
            }
        } else {
            newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating?.toInt()
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
        val entryContent = document.selectFirst(".entry-content") ?: return false

        // Find all download links
        entryContent.select("a[href*='tech.unblockedgames.world'], a[href*='drive.google.com']").forEach { linkElement ->
            val linkUrl = linkElement.attr("href")
            val linkText = linkElement.text().trim()
            
            // Only include download links
            if (!linkText.contains("Download", ignoreCase = true) && 
                !linkText.contains("G-Drive", ignoreCase = true)) {
                return@forEach
            }
            
            val parentElement = linkElement.parent() ?: return@forEach
            val fullText = parentElement.text()
            val linkIndex = fullText.indexOf(linkText)
            if (linkIndex <= 0) return@forEach
            
            val fileInfo = fullText.substring(0, linkIndex).trim()
            
            // Filter: Only include if file info contains quality/codec indicators
            val hasValidFileInfo = fileInfo.contains("1080p", ignoreCase = true) ||
                                  fileInfo.contains("2160p", ignoreCase = true) ||
                                  fileInfo.contains("720p", ignoreCase = true) ||
                                  fileInfo.contains("4K", ignoreCase = true) ||
                                  fileInfo.contains("HEVC", ignoreCase = true) ||
                                  fileInfo.contains("x264", ignoreCase = true) ||
                                  fileInfo.contains("x265", ignoreCase = true) ||
                                  fileInfo.contains("WEB-DL", ignoreCase = true) ||
                                  fileInfo.contains("BluRay", ignoreCase = true) ||
                                  fileInfo.contains("REMUX", ignoreCase = true)
            
            if (!hasValidFileInfo) return@forEach
            
            val fileSize = extractFileSize(fileInfo)
            if (fileSize.isEmpty()) return@forEach
            
            val quality = extractQualityFromFileName(fileInfo)
            val displayName = fileInfo.take(150)
            
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$displayName [$fileSize]",
                    url = linkUrl,
                    referer = mainUrl,
                    quality = quality,
                    isM3u8 = false
                )
            )
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
            val titleElement = element.selectFirst("h2.entry-title a, h1.entry-title a") ?: return null
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
            Log.e("UHDMovies", "Failed to parse search result", e)
            null
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeLinks = document.select("a[href*='episode'], a[href*='e0']")
        
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
            ?: document.selectFirst("img.wp-post-image")?.attr("abs:src")
            ?: document.selectFirst("article img")?.attr("abs:src")
    }

    private fun extractPlot(document: Document): String? {
        return document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()
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
    
    private fun extractQualityFromFileName(fileName: String): Int {
        return when {
            fileName.contains("2160p", ignoreCase = true) || 
            fileName.contains("4K", ignoreCase = true) || 
            fileName.contains("UHD", ignoreCase = true) -> Qualities.P2160.value
            fileName.contains("1080p", ignoreCase = true) || 
            fileName.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
            fileName.contains("720p", ignoreCase = true) || 
            fileName.contains("HD", ignoreCase = true) -> Qualities.P720.value
            fileName.contains("480p", ignoreCase = true) -> Qualities.P480.value
            fileName.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }
    
    private fun extractFileSize(text: String): String {
        val sizeRegex = """[\\[\\(]?(\\d+\\.?\\d*)\\s*(GB|MB|TB)[\\]\\)]?""".toRegex(RegexOption.IGNORE_CASE)
        val match = sizeRegex.find(text)
        return match?.value?.replace("[", "")?.replace("]", "")?.replace("(", "")?.replace(")", "")?.trim() ?: ""
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace("Download", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractRating(document: Document): Float? {
        val ratingRegex = """IMDb[:\\s]+(\\d+\\.?\\d*)""".toRegex(RegexOption.IGNORE_CASE)
        val text = document.text()
        val match = ratingRegex.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }
}
