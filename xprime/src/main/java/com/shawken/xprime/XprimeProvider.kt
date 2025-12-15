package com.shawken.xprime

import com.shawken.app.plugins.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class XprimeProvider : PluginProvider() {

    override var mainUrl = "https://xprime.today"
    override var name = "XPrime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "84259f99204eeb7d45c7e3d8e36c6123"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val apiBaseUrl = "https://mzt4pr8wlkxnv0qsha5g.website"

    private val client = OkHttpClient()

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encodedQuery&page=1&include_adult=false"
        
        val json = fetchJson(url)
        val results = mutableListOf<SearchResponse>()
        
        val resultsArray = json.optJSONArray("results") ?: return emptyList()
        
        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.getJSONObject(i)
            val mediaType = item.optString("media_type")
            
            if (mediaType == "movie" || mediaType == "tv") {
                val id = item.optInt("id")
                val title = item.optString("title").ifEmpty { item.optString("name") }
                val posterPath = item.optString("poster_path")
                val posterUrl = if (posterPath.isNotEmpty() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else null
                val year = item.optString("release_date").ifEmpty { item.optString("first_air_date") }.take(4).toIntOrNull()
                
                results.add(newMovieSearchResponse(
                    name = title,
                    url = if (mediaType == "movie") id.toString() else "tv:$id",
                    type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
                ) {
                    this.posterUrl = posterUrl
                    this.year = year
                })
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val isTv = url.startsWith("tv:")
        val id = if (isTv) url.substring(3) else url
        val type = if (isTv) "tv" else "movie"
        
        val tmdbUrl = "$tmdbBaseUrl/$type/$id?api_key=$tmdbApiKey&append_to_response=external_ids,recommendations"
        val json = fetchJson(tmdbUrl)
        
        val title = json.optString("title").ifEmpty { json.optString("name") }
        val posterPath = json.optString("poster_path")
        val posterUrl = if (posterPath.isNotEmpty() && posterPath != "null") "https://image.tmdb.org/t/p/original$posterPath" else null
        val year = json.optString("release_date").ifEmpty { json.optString("first_air_date") }.take(4).toIntOrNull()
        val plot = json.optString("overview")
        val rating = json.optDouble("vote_average").toInt()
        
        val genres = json.optJSONArray("genres")
        val tags = mutableListOf<String>()
        if (genres != null) {
            for (i in 0 until genres.length()) {
                tags.add(genres.getJSONObject(i).optString("name"))
            }
        }
        
        val recommendations = mutableListOf<SearchResponse>()
        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")
        if (recs != null) {
             for (i in 0 until recs.length()) {
                val item = recs.getJSONObject(i)
                val rTitle = item.optString("title").ifEmpty { item.optString("name") }
                val rPoster = item.optString("poster_path")
                val rUrl = if (rPoster.isNotEmpty() && rPoster != "null") "https://image.tmdb.org/t/p/w500$rPoster" else null
                val rYear = item.optString("release_date").ifEmpty { item.optString("first_air_date") }.take(4).toIntOrNull()
                 val rMediaType = item.optString("media_type", "movie") 
                 
                recommendations.add(newMovieSearchResponse(
                    name = rTitle,
                    url = if (rMediaType == "tv") "tv:${item.optInt("id")}" else item.optInt("id").toString(),
                    type = if (rMediaType == "tv") TvType.TvSeries else TvType.Movie
                ) {
                    this.posterUrl = rUrl
                    this.year = rYear
                })
            }
        }

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seasons = json.optJSONArray("seasons")
            
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val season = seasons.getJSONObject(i)
                    val seasonNumber = season.optInt("season_number")
                    if (seasonNumber > 0) {
                        val seasonUrl = "$tmdbBaseUrl/tv/$id/season/$seasonNumber?api_key=$tmdbApiKey"
                        try {
                            val sJson = fetchJson(seasonUrl)
                            val eps = sJson.optJSONArray("episodes")
                            if (eps != null) {
                                for (j in 0 until eps.length()) {
                                    val ep = eps.getJSONObject(j)
                                    val epNum = ep.optInt("episode_number")
                                    val epName = ep.optString("name")
                                    val epDate = ep.optString("air_date")
                                    
                                    val stillPath = ep.optString("still_path")
                                    val seasonPosterPath = sJson.optString("poster_path")
                                    
                                    val epThumb = if (stillPath.isNotEmpty() && stillPath != "null") {
                                        "https://image.tmdb.org/t/p/w300$stillPath"
                                    } else if (seasonPosterPath.isNotEmpty() && seasonPosterPath != "null") {
                                        "https://image.tmdb.org/t/p/w300$seasonPosterPath"
                                    } else {
                                        posterUrl
                                    }

                                    episodes.add(newEpisode("tv:$id:$seasonNumber:$epNum") {
                                        this.name = epName
                                        this.season = seasonNumber
                                        this.episode = epNum
                                        this.posterUrl = epThumb
                                        this.description = ep.optString("overview")
                                        addDate(epDate)
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse dataUrl
        val isTv = data.startsWith("tv:")
        val parts = data.split(":")
        val tmdbId = if (isTv) parts[1] else parts[0]
        val season = if (isTv && parts.size > 2) parts[2] else null
        val episode = if (isTv && parts.size > 3) parts[3] else null
        
        // Try Primenet
        try {
            var primenetUrl = "$apiBaseUrl/primenet?id=$tmdbId"
            if (isTv && season != null && episode != null) {
                primenetUrl += "&season=$season&episode=$episode"
            }
            
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            
            val response = fetchJson(primenetUrl, headers)
            val streamUrl = response.optString("url")
            
            if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                callback.invoke(ExtractorLink(
                    source = "Primenet",
                    name = "Primenet",
                    url = streamUrl,
                    referer = mainUrl,
                    quality = 1080,
                    isM3u8 = streamUrl.contains(".m3u8")
                ))
            }
        } catch (e: Exception) {
            // Primenet failed, continue to other sources
        }
        
        // Try VidSrc.to
        try {
            val vidSrcUrl = if (isTv && season != null && episode != null) {
                "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://vidsrc.to/embed/movie/$tmdbId"
            }
            
            callback.invoke(ExtractorLink(
                source = "VidSrc",
                name = "VidSrc",
                url = vidSrcUrl,
                referer = "https://vidsrc.to/",
                quality = 1080,
                isM3u8 = false
            ))
        } catch (e: Exception) {
            // VidSrc failed
        }
        
        // Try SuperEmbed
        try {
            val superEmbedUrl = if (isTv && season != null && episode != null) {
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            } else {
                "https://multiembed.mov/?video_id=$tmdbId&tmdb=1"
            }
            
            callback.invoke(ExtractorLink(
                source = "SuperEmbed",
                name = "SuperEmbed",
                url = superEmbedUrl,
                referer = "https://multiembed.mov/",
                quality = 1080,
                isM3u8 = false
            ))
        } catch (e: Exception) {
            // SuperEmbed failed
        }
        
        // Try VidSrc.pro
        try {
            val vidSrcProUrl = if (isTv && season != null && episode != null) {
                "https://vidsrc.pro/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://vidsrc.pro/embed/movie/$tmdbId"
            }
            
            callback.invoke(ExtractorLink(
                source = "VidSrc Pro",
                name = "VidSrc Pro",
                url = vidSrcProUrl,
                referer = "https://vidsrc.pro/",
                quality = 1080,
                isM3u8 = false
            ))
        } catch (e: Exception) {
            // VidSrc Pro failed
        }
        
        // Try 2embed
        try {
            val embedUrl = if (isTv && season != null && episode != null) {
                "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode"
            } else {
                "https://www.2embed.cc/embed/$tmdbId"
            }
            
            callback.invoke(ExtractorLink(
                source = "2embed",
                name = "2embed",
                url = embedUrl,
                referer = "https://www.2embed.cc/",
                quality = 1080,
                isM3u8 = false
            ))
        } catch (e: Exception) {
            // 2embed failed
        }
        
        return true
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Trending
        val trendingUrl = "$tmdbBaseUrl/trending/all/day?api_key=$tmdbApiKey"
        try {
            val json = fetchJson(trendingUrl)
            val results = json.optJSONArray("results")
            val list = mutableListOf<SearchResponse>()
            if (results != null) {
                for (i in 0 until results.length()) {
                     val item = results.getJSONObject(i)
                     val mediaType = item.optString("media_type")
                     if (mediaType == "movie" || mediaType == "tv") {
                         val title = item.optString("title").ifEmpty { item.optString("name") }
                         val poster = item.optString("poster_path")
                         list.add(newMovieSearchResponse(
                             name = title,
                             url = if (mediaType == "movie") item.optInt("id").toString() else "tv:${item.optInt("id")}",
                             type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
                         ) {
                             this.posterUrl = if (poster.isNotEmpty()) "https://image.tmdb.org/t/p/w500$poster" else null
                         })
                     }
                }
            }
            return newHomePageResponse("Trending", list)
        } catch (e: Exception) {
            return newHomePageResponse("Trending", emptyList())
        }
    }

    private fun fetchJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        return JSONObject(body)
    }
}
