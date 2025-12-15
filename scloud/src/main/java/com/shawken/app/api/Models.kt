package com.shawken.app.api

/**
 * Content type enumeration
 */
enum class ContentType {
    MOVIE,
    TV_SERIES,
    ANIME,
    DOCUMENTARY,
    OTHER
}

/**
 * Search response containing results and pagination info
 */
data class SearchResponse(
    val results: List<SearchResult>,
    val hasNextPage: Boolean = false
)

/**
 * Individual search result
 */
data class SearchResult(
    val name: String,
    val url: String,
    val posterUrl: String? = null,
    val type: ContentType,
    val quality: String? = null,
    val year: Int? = null
)

/**
 * Base class for load responses
 */
sealed class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val posterUrl: String?
    abstract val year: Int?
    abstract val plot: String?
    abstract val rating: Float?
    abstract val tags: List<String>
}

/**
 * Movie load response
 */
data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    val dataUrl: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Float? = null,
    override val tags: List<String> = emptyList(),
    val duration: Int? = null,
    val recommendations: List<SearchResult> = emptyList()
) : LoadResponse()

/**
 * TV Series load response
 */
data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Float? = null,
    override val tags: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val recommendations: List<SearchResult> = emptyList()
) : LoadResponse()

/**
 * Episode information for TV series
 */
data class Episode(
    val name: String,
    val season: Int,
    val episode: Int,
    val dataUrl: String,
    val posterUrl: String? = null,
    val description: String? = null,
    val date: String? = null
)

/**
 * Extractor link for video playback
 */
data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = 0,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Subtitle file information
 */
data class SubtitleFile(
    val url: String,
    val lang: String,
    val format: SubtitleFormat = SubtitleFormat.SRT
)

enum class SubtitleFormat {
    SRT, VTT, ASS
}

/**
 * Home page response
 */
data class HomePageResponse(
    val items: List<HomePageList>
)

data class HomePageList(
    val name: String,
    val list: List<SearchResult>
)

/**
 * Filter classes for search
 */
sealed class Filter {
    abstract val name: String
    abstract val id: String

    data class Text(
        override val name: String,
        override val id: String,
        val placeholder: String = ""
    ) : Filter()

    data class Select(
        override val name: String,
        override val id: String,
        val options: List<String>,
        val defaultIndex: Int = 0
    ) : Filter()

    data class CheckBox(
        override val name: String,
        override val id: String,
        val defaultValue: Boolean = false
    ) : Filter()
}
