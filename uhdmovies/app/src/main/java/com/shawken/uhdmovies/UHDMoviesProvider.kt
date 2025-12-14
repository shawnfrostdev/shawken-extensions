package com.shawken.uhdmovies

import android.content.Context
import android.util.Log
import com.shawken.app.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class UHDMoviesProvider(context: Context) : Provider(context) {

    private val TAG = "UHDMoviesProvider"

    override val name = "UHDMovies"
    override val lang = "en"
    override val baseUrl = "https://uhdmovies.stream"
    override val description = "4K Dual Audio Movies, Ultra HD movies, 1080p Movies"
    override val iconUrl = "$baseUrl/favicon.ico"

    override val supportsSearch = true
    override val supportsLatest = true
    override val supportsTrending = false

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

    /**
     * Search for content on UHDMovies
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Map<String, String>
    ): SearchResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Searching for: $query, page: $page")
            
            val searchUrl = "$baseUrl/page/$page/?s=${query.replace(" ", "+")}"
            val document = fetchDocument(searchUrl)

            val results = document.select("article.post").mapNotNull { element ->
                parseSearchResult(element)
            }

            val hasNextPage = document.select(".pagination .next").isNotEmpty()

            Log.d(TAG, "Found ${results.size} results")
            SearchResponse(results, hasNextPage)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            SearchResponse(emptyList(), false)
        }
    }

    /**
     * Load detailed information about a movie/series
     */
    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading: $url")
            
            val document = fetchDocument(url)
            
            val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
                ?: document.selectFirst("title")?.text()?.split("-")?.firstOrNull()?.trim()
                ?: "Unknown"

            // Extract year from title
            val yearRegex = """\((\d{4})\)""".toRegex()
            val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

            // Determine if it's a TV series
            val isSeries = title.contains("Season", ignoreCase = true) || 
                          title.contains("S0", ignoreCase = true) ||
                          document.select("a[href*='season']").isNotEmpty()

            // Extract plot/description
            val plot = extractPlot(document)
            
            // Extract poster
            val posterUrl = extractPosterUrl(document)
            
            // Extract tags from title
            val tags = extractTags(title)
            
            // Try to extract IMDb rating if available
            val rating = extractRating(document)

            if (isSeries) {
                // Parse as TV Series
                val episodes = parseEpisodes(document, url)
                
                TvSeriesLoadResponse(
                    name = cleanTitle(title),
                    url = url,
                    posterUrl = posterUrl,
                    year = year,
                    plot = plot,
                    rating = rating,
                    tags = tags,
                    episodes = episodes
                )
            } else {
                // Parse as Movie
                MovieLoadResponse(
                    name = cleanTitle(title),
                    url = url,
                    dataUrl = url, // The same URL will be used to extract links
                    posterUrl = posterUrl,
                    year = year,
                    plot = plot,
                    rating = rating,
                    tags = tags,
                    duration = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            throw e
        }
    }

    /**
     * Extract streaming links from the page
     * ONLY extracts actual movie/show download links, not navigation or other links
     */
    override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading links from: $dataUrl")
            
            val document = fetchDocument(dataUrl)
            val links = mutableListOf<ExtractorLink>()

            // Get the entry content where download links are located
            val entryContent = document.selectFirst(".entry-content") ?: return@withContext emptyList()

            // Find all download links - only from tech.unblockedgames.world or drive.google.com
            entryContent.select("a[href*='tech.unblockedgames.world'], a[href*='drive.google.com']").forEach { linkElement ->
                val linkUrl = linkElement.attr("href")
                val linkText = linkElement.text().trim()
                
                // STRICT FILTER: Only include if it's explicitly a download link
                if (!linkText.contains("Download", ignoreCase = true) && 
                    !linkText.contains("G-Drive", ignoreCase = true)) {
                    return@forEach
                }
                
                // Get the text content before this link (contains file info)
                val parentElement = linkElement.parent() ?: return@forEach
                val fullText = parentElement.text()
                
                // Extract the file information line (before the download link)
                val linkIndex = fullText.indexOf(linkText)
                if (linkIndex <= 0) return@forEach
                
                val fileInfo = fullText.substring(0, linkIndex).trim()
                
                // FILTER: Only include if file info contains quality/codec indicators
                // This ensures we only get actual movie files, not category/navigation links
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
                
                if (!hasValidFileInfo) {
                    Log.d(TAG, "Skipping link without valid file info: $fileInfo")
                    return@forEach
                }
                
                // Extract file size - FILTER: Only include if file size is present
                val fileSize = extractFileSize(fileInfo)
                if (fileSize.isEmpty()) {
                    Log.d(TAG, "Skipping link without file size: $fileInfo")
                    return@forEach
                }
                
                // Extract quality from file name
                val quality = extractQualityFromFileName(fileInfo)
                
                // Create clean display name from file info
                val displayName = fileInfo.take(150) // Limit length
                
                links.add(
                    ExtractorLink(
                        source = name,
                        name = "$displayName [$fileSize]",
                        url = linkUrl,
                        referer = baseUrl,
                        quality = quality,
                        isM3u8 = false,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to baseUrl
                        )
                    )
                )
            }

            Log.d(TAG, "Found ${links.size} valid download links")
            links
        } catch (e: Exception) {
            Log.e(TAG, "Load links failed", e)
            emptyList()
        }
    }

    /**
     * Get main page content
     */
    override suspend fun getMainPage(): HomePageResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading main page")
            
            val document = fetchDocument(baseUrl)
            val sections = mutableListOf<HomePageList>()

            // Get latest posts
            val latestPosts = document.select("article.post").take(10).mapNotNull { element ->
                parseSearchResult(element)
            }

            if (latestPosts.isNotEmpty()) {
                sections.add(HomePageList("Latest Releases", latestPosts))
            }

            HomePageResponse(sections)
        } catch (e: Exception) {
            Log.e(TAG, "Get main page failed", e)
            HomePageResponse(emptyList())
        }
    }

    // Helper functions

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response")
        return Jsoup.parse(html)
    }

    private fun parseSearchResult(element: Element): SearchResult? {
        return try {
            val titleElement = element.selectFirst("h2.entry-title a, h1.entry-title a") ?: return null
            val title = titleElement.text().trim()
            val url = titleElement.attr("href")
            
            val posterUrl = element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")

            // Determine content type
            val isSeries = title.contains("Season", ignoreCase = true) || 
                          title.contains("S0", ignoreCase = true)
            
            val type = when {
                isSeries -> ContentType.TV_SERIES
                title.contains("Anime", ignoreCase = true) -> ContentType.ANIME
                else -> ContentType.MOVIE
            }

            // Extract quality
            val quality = when {
                title.contains("4K", ignoreCase = true) || title.contains("2160p", ignoreCase = true) -> "4K"
                title.contains("1080p", ignoreCase = true) -> "1080p"
                title.contains("720p", ignoreCase = true) -> "720p"
                else -> null
            }

            // Extract year
            val yearRegex = """\((\d{4})\)""".toRegex()
            val year = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

            SearchResult(
                name = title,
                url = url,
                posterUrl = posterUrl,
                type = type,
                quality = quality,
                year = year
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search result", e)
            null
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Try to find episode links in the content
        val episodeLinks = document.select("a[href*='episode'], a[href*='e0']")
        
        episodeLinks.forEachIndexed { index, element ->
            val episodeUrl = element.attr("href")
            val episodeText = element.text()
            
            // Try to extract season and episode numbers
            val seasonEpisodeRegex = """S(\d+)E(\d+)""".toRegex(RegexOption.IGNORE_CASE)
            val match = seasonEpisodeRegex.find(episodeText)
            
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodeNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
            
            episodes.add(
                Episode(
                    name = episodeText.ifEmpty { "Episode $episodeNum" },
                    season = season,
                    episode = episodeNum,
                    dataUrl = episodeUrl,
                    posterUrl = null,
                    description = null
                )
            )
        }
        
        // If no episodes found, create a single episode pointing to the main URL
        if (episodes.isEmpty()) {
            episodes.add(
                Episode(
                    name = "Full Season",
                    season = 1,
                    episode = 1,
                    dataUrl = baseUrl,
                    posterUrl = null,
                    description = null
                )
            )
        }
        
        return episodes
    }

    private fun extractPosterUrl(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.wp-post-image")?.attr("src")
            ?: document.selectFirst("article img")?.attr("src")
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

    private fun extractQuality(text: String): Int {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4K", ignoreCase = true) -> 2160
            text.contains("1080p", ignoreCase = true) -> 1080
            text.contains("720p", ignoreCase = true) -> 720
            text.contains("480p", ignoreCase = true) -> 480
            else -> 0
        }
    }
    
    private fun extractQualityFromFileName(fileName: String): Int {
        return when {
            fileName.contains("2160p", ignoreCase = true) || 
            fileName.contains("4K", ignoreCase = true) || 
            fileName.contains("UHD", ignoreCase = true) -> 2160
            fileName.contains("1080p", ignoreCase = true) || 
            fileName.contains("FHD", ignoreCase = true) -> 1080
            fileName.contains("720p", ignoreCase = true) || 
            fileName.contains("HD", ignoreCase = true) -> 720
            fileName.contains("480p", ignoreCase = true) -> 480
            fileName.contains("360p", ignoreCase = true) -> 360
            else -> 1080 // Default to 1080p if not specified
        }
    }
    
    private fun extractFileSize(text: String): String {
        // Match patterns like [15.52 GB], (15.52 GB), 15.52 GB, etc.
        val sizeRegex = """[\[\(]?(\d+\.?\d*)\s*(GB|MB|TB)[\]\)]?""".toRegex(RegexOption.IGNORE_CASE)
        val match = sizeRegex.find(text)
        return match?.value?.replace("[", "")?.replace("]", "")?.replace("(", "")?.replace(")", "")?.trim() ?: ""
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace("Download", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractRating(document: Document): Float? {
        // Try to find IMDb rating in the content
        val ratingRegex = """IMDb[:\s]+(\d+\.?\d*)""".toRegex(RegexOption.IGNORE_CASE)
        val text = document.text()
        val match = ratingRegex.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }
}
