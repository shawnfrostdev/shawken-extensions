package com.shawken.app.api

import android.content.Context

/**
 * Base Provider class that all extensions must extend
 */
abstract class Provider(protected val context: Context) {
    
    // Required metadata
    abstract val name: String
    abstract val lang: String
    abstract val baseUrl: String
    open val description: String = ""
    open val iconUrl: String = ""
    
    // Capability flags
    open val supportsSearch: Boolean = true
    open val supportsLatest: Boolean = false
    open val supportsTrending: Boolean = false
    
    /**
     * Search for content
     */
    abstract suspend fun search(
        query: String,
        page: Int = 1,
        filters: Map<String, String> = emptyMap()
    ): SearchResponse
    
    /**
     * Load detailed information about a content item
     */
    abstract suspend fun load(url: String): LoadResponse
    
    /**
     * Extract streaming links from a data URL
     */
    abstract suspend fun loadLinks(dataUrl: String): List<ExtractorLink>
    
    /**
     * Get main page content (optional)
     */
    open suspend fun getMainPage(): HomePageResponse {
        return HomePageResponse(emptyList())
    }
    
    /**
     * Get available filters (optional)
     */
    open suspend fun getFilters(): List<Filter> {
        return emptyList()
    }
}
