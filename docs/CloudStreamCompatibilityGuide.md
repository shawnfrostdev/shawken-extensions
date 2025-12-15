# CloudStream Extension Compatibility Guide for Shawken

> **Complete guide to making Shawken compatible with CloudStream extensions**

## 📋 Table of Contents

1. [Overview](#overview)
2. [Architecture Comparison](#architecture-comparison)
3. [Implementation Strategy](#implementation-strategy)
4. [Phase 1: Core API Compatibility Layer](#phase-1-core-api-compatibility-layer)
5. [Phase 2: Plugin System](#phase-2-plugin-system)
6. [Phase 3: Extension Manager](#phase-3-extension-manager)
7. [Phase 4: Extractor System](#phase-4-extractor-system)
8. [Phase 5: Testing & Validation](#phase-5-testing--validation)
9. [Migration Path](#migration-path)
10. [Limitations & Considerations](#limitations--considerations)

---

## 🎯 Overview

This guide explains how to make Shawken compatible with CloudStream extensions, allowing users to install and use CloudStream plugins directly in Shawken.

### Why This Matters

- **Massive Extension Library**: Access to 80+ CloudStream extensions
- **Active Community**: CloudStream has a large developer community
- **Proven Scrapers**: Battle-tested extraction logic
- **Dual Compatibility**: Support both Shawken and CloudStream extensions

### Key Challenges

1. **Different Base Classes**: CloudStream uses `MainAPI()`, Shawken uses `Provider(context)`
2. **Plugin vs APK**: CloudStream uses plugin system, Shawken uses standalone APKs
3. **Different Loading Mechanisms**: CloudStream uses annotations, Shawken uses PackageManager
4. **API Differences**: Method signatures and data classes differ

---

## 🏗️ Architecture Comparison

### CloudStream Architecture

```
CloudStream App
├── Plugin Manager (discovers @CloudstreamPlugin)
├── MainAPI Registry (stores providers)
├── Extractor Registry (stores extractors)
└── Extensions (Android Libraries)
    └── Plugin Class
        ├── MainAPI implementations
        └── ExtractorApi implementations
```

### Shawken Architecture (Current)

```
Shawken App
├── ExtensionManager (scans PackageManager)
├── Provider Registry (stores providers)
└── Extensions (Android APKs)
    └── Provider Class (extends Provider)
```

### Proposed Hybrid Architecture

```
Shawken App
├── Extension Manager (unified)
│   ├── APK Scanner (for Shawken extensions)
│   └── Plugin Scanner (for CloudStream extensions)
├── Provider Registry (unified)
│   ├── Shawken Providers
│   └── CloudStream Providers (wrapped)
├── Extractor Registry (new)
└── Compatibility Layer
    ├── MainAPI → Provider Adapter
    ├── CloudStream Data Class Converters
    └── Plugin Loader
```

---

## 📝 Implementation Strategy

### Approach: Adapter Pattern

We'll create an **adapter layer** that translates CloudStream's API to Shawken's API without modifying either:

```
CloudStream Extension → Adapter → Shawken Core
```

This allows:
- ✅ CloudStream extensions work as-is
- ✅ Shawken extensions continue working
- ✅ No breaking changes to existing code
- ✅ Gradual migration path

---

## 🔧 Phase 1: Core API Compatibility Layer

### Step 1.1: Create CloudStream API Package

Create a new module in your Shawken app:

**File: `app/src/main/java/com/shawken/app/cloudstream/CloudStreamAPI.kt`**

```kotlin
package com.shawken.app.cloudstream

import android.content.Context
import com.shawken.app.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Base class that mimics CloudStream's MainAPI
 * This allows CloudStream extensions to work in Shawken
 */
abstract class MainAPI {
    // Required properties
    abstract var mainUrl: String
    abstract var name: String
    abstract var lang: String
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    
    // Main methods
    abstract suspend fun search(query: String): List<SearchResponse>
    abstract suspend fun load(url: String): LoadResponse
    abstract suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean
    
    // Optional methods
    open suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return HomePageResponse(emptyList())
    }
}

/**
 * CloudStream TvType enum
 */
enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    Cartoon,
    Documentary,
    OVA,
    AsianDrama,
    Live,
    NSFW,
    Others
}

/**
 * CloudStream SearchResponse
 */
data class SearchResponse(
    val name: String,
    val url: String,
    val apiName: String,
    val type: TvType?,
    val posterUrl: String?,
    val year: Int?,
    val quality: SearchQuality?
)

enum class SearchQuality {
    SD, HD, BlueRay, FourK, UHD, SDR, HDR, WebRip, CAM
}

/**
 * CloudStream LoadResponse (sealed class)
 */
sealed class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    abstract val type: TvType
    abstract val posterUrl: String?
    abstract val year: Int?
    abstract val plot: String?
    abstract val rating: Int?
    abstract val tags: List<String>?
    abstract val duration: Int?
    abstract val trailers: List<String>?
    abstract val recommendations: List<SearchResponse>?
    abstract val actors: List<ActorData>?
}

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    val dataUrl: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailers: List<String>? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorData>? = null,
    val comingSoon: Boolean = false
) : LoadResponse() {
    override val type = TvType.Movie
}

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    val episodes: List<Episode>,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailers: List<String>? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorData>? = null,
    val showStatus: ShowStatus? = null
) : LoadResponse() {
    override val type = TvType.TvSeries
}

data class Episode(
    val data: String,  // dataUrl for loadLinks
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String? = null,
    val rating: Int? = null,
    val description: String? = null,
    val date: String? = null
)

enum class ShowStatus {
    Ongoing,
    Completed,
    Cancelled
}

data class ActorData(
    val actor: Actor,
    val roleString: String? = null
)

data class Actor(
    val name: String,
    val image: String? = null
)

/**
 * CloudStream ExtractorLink
 */
data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = 0,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val extractorData: String? = null
)

/**
 * CloudStream SubtitleFile
 */
data class SubtitleFile(
    val url: String,
    val lang: String
)

/**
 * HomePage support
 */
data class HomePageResponse(
    val items: List<HomePageList>
)

data class HomePageList(
    val name: String,
    val list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

/**
 * Helper functions to create responses
 */
fun newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    initializer: SearchResponse.() -> Unit = {}
): SearchResponse {
    val response = SearchResponse(
        name = name,
        url = url,
        apiName = "",
        type = type,
        posterUrl = null,
        year = null,
        quality = null
    )
    response.initializer()
    return response
}

fun newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    initializer: MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    val response = MovieLoadResponse(
        name = name,
        url = url,
        apiName = "",
        dataUrl = dataUrl
    )
    response.initializer()
    return response
}

fun newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    initializer: TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    val response = TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = "",
        episodes = episodes
    )
    response.initializer()
    return response
}

fun newEpisode(
    data: String,
    initializer: Episode.() -> Unit = {}
): Episode {
    val episode = Episode(
        data = data,
        name = null,
        season = null,
        episode = null
    )
    episode.initializer()
    return episode
}

fun newHomePageResponse(
    name: String,
    list: List<SearchResponse>,
    hasNext: Boolean = false
): HomePageResponse {
    return HomePageResponse(
        items = listOf(HomePageList(name, list))
    )
}

/**
 * Helper to create main page configuration
 */
fun mainPageOf(vararg pages: Pair<String, String>): List<MainPageRequest> {
    return pages.map { (data, name) ->
        MainPageRequest(name, data)
    }
}
```

### Step 1.2: Create Adapter Class

**File: `app/src/main/java/com/shawken/app/cloudstream/CloudStreamAdapter.kt`**

```kotlin
package com.shawken.app.cloudstream

import android.content.Context
import com.shawken.app.api.*
import com.shawken.app.api.ContentType as ShawkenContentType
import com.shawken.app.api.SearchResponse as ShawkenSearchResponse
import com.shawken.app.api.LoadResponse as ShawkenLoadResponse
import com.shawken.app.api.ExtractorLink as ShawkenExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter that wraps a CloudStream MainAPI and makes it work as a Shawken Provider
 */
class CloudStreamAdapter(
    context: Context,
    private val mainAPI: MainAPI
) : Provider(context) {
    
    override val name: String get() = mainAPI.name
    override val lang: String get() = mainAPI.lang
    override val baseUrl: String get() = mainAPI.mainUrl
    override val description: String = "CloudStream Extension"
    override val iconUrl: String = ""
    
    override val supportsSearch: Boolean = true
    override val supportsLatest: Boolean = mainAPI.hasMainPage
    override val supportsTrending: Boolean = false
    
    /**
     * Convert CloudStream search to Shawken search
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Map<String, String>
    ): com.shawken.app.api.SearchResponse = withContext(Dispatchers.IO) {
        try {
            val csResults = mainAPI.search(query)
            val shawkenResults = csResults.map { csResult ->
                convertSearchResponse(csResult)
            }
            com.shawken.app.api.SearchResponse(
                results = shawkenResults,
                hasNextPage = false // CloudStream doesn't have pagination in search
            )
        } catch (e: Exception) {
            com.shawken.app.api.SearchResponse(emptyList(), false)
        }
    }
    
    /**
     * Convert CloudStream load to Shawken load
     */
    override suspend fun load(url: String): ShawkenLoadResponse = withContext(Dispatchers.IO) {
        val csLoadResponse = mainAPI.load(url)
        convertLoadResponse(csLoadResponse)
    }
    
    /**
     * Convert CloudStream loadLinks to Shawken loadLinks
     */
    override suspend fun loadLinks(dataUrl: String): List<ShawkenExtractorLink> = withContext(Dispatchers.IO) {
        val links = mutableListOf<ShawkenExtractorLink>()
        
        mainAPI.loadLinks(
            data = dataUrl,
            isCasting = false,
            subtitleCallback = { /* Handle subtitles if needed */ },
            callback = { csLink ->
                links.add(convertExtractorLink(csLink))
            }
        )
        
        links
    }
    
    /**
     * Convert CloudStream main page to Shawken main page
     */
    override suspend fun getMainPage(): com.shawken.app.api.HomePageResponse = withContext(Dispatchers.IO) {
        try {
            if (!mainAPI.hasMainPage) {
                return@withContext com.shawken.app.api.HomePageResponse(emptyList())
            }
            
            val csHomePage = mainAPI.getMainPage(1, MainPageRequest("Home", ""))
            val sections = csHomePage.items.map { section ->
                com.shawken.app.api.HomePageList(
                    name = section.name,
                    list = section.list.map { convertSearchResponse(it) }
                )
            }
            com.shawken.app.api.HomePageResponse(sections)
        } catch (e: Exception) {
            com.shawken.app.api.HomePageResponse(emptyList())
        }
    }
    
    // ========== CONVERSION FUNCTIONS ==========
    
    private fun convertSearchResponse(cs: SearchResponse): com.shawken.app.api.SearchResult {
        return com.shawken.app.api.SearchResult(
            name = cs.name,
            url = cs.url,
            posterUrl = cs.posterUrl,
            type = convertTvType(cs.type),
            quality = cs.quality?.name,
            year = cs.year
        )
    }
    
    private fun convertLoadResponse(cs: LoadResponse): ShawkenLoadResponse {
        return when (cs) {
            is MovieLoadResponse -> {
                com.shawken.app.api.MovieLoadResponse(
                    name = cs.name,
                    url = cs.url,
                    dataUrl = cs.dataUrl,
                    posterUrl = cs.posterUrl,
                    year = cs.year,
                    plot = cs.plot,
                    rating = cs.rating?.toFloat(),
                    tags = cs.tags ?: emptyList(),
                    duration = cs.duration,
                    recommendations = cs.recommendations?.map { convertSearchResponse(it) } ?: emptyList()
                )
            }
            is TvSeriesLoadResponse -> {
                val episodes = cs.episodes.map { csEpisode ->
                    com.shawken.app.api.Episode(
                        name = csEpisode.name ?: "Episode ${csEpisode.episode}",
                        season = csEpisode.season ?: 1,
                        episode = csEpisode.episode ?: 1,
                        dataUrl = csEpisode.data,
                        posterUrl = csEpisode.posterUrl,
                        description = csEpisode.description,
                        date = csEpisode.date
                    )
                }
                com.shawken.app.api.TvSeriesLoadResponse(
                    name = cs.name,
                    url = cs.url,
                    posterUrl = cs.posterUrl,
                    year = cs.year,
                    plot = cs.plot,
                    rating = cs.rating?.toFloat(),
                    tags = cs.tags ?: emptyList(),
                    episodes = episodes,
                    recommendations = cs.recommendations?.map { convertSearchResponse(it) } ?: emptyList()
                )
            }
            else -> throw IllegalArgumentException("Unknown LoadResponse type")
        }
    }
    
    private fun convertExtractorLink(cs: ExtractorLink): ShawkenExtractorLink {
        return ShawkenExtractorLink(
            source = cs.source,
            name = cs.name,
            url = cs.url,
            referer = cs.referer,
            quality = cs.quality,
            isM3u8 = cs.isM3u8,
            headers = cs.headers
        )
    }
    
    private fun convertTvType(cs: TvType?): ShawkenContentType {
        return when (cs) {
            TvType.Movie -> ShawkenContentType.MOVIE
            TvType.TvSeries -> ShawkenContentType.TV_SERIES
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> ShawkenContentType.ANIME
            TvType.Documentary -> ShawkenContentType.DOCUMENTARY
            else -> ShawkenContentType.OTHER
        }
    }
}
```

---

## 🔌 Phase 2: Plugin System

### Step 2.1: Create Plugin Annotation

**File: `app/src/main/java/com/shawken/app/cloudstream/CloudstreamPlugin.kt`**

```kotlin
package com.shawken.app.cloudstream

/**
 * Annotation to mark CloudStream plugin classes
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

/**
 * Base class for CloudStream plugins
 */
abstract class BasePlugin {
    abstract fun load()
    
    protected fun registerMainAPI(api: MainAPI) {
        CloudStreamPluginRegistry.registerMainAPI(api)
    }
    
    protected fun registerExtractorAPI(extractor: ExtractorApi) {
        CloudStreamPluginRegistry.registerExtractor(extractor)
    }
}

/**
 * Registry to store loaded plugins
 */
object CloudStreamPluginRegistry {
    private val mainAPIs = mutableListOf<MainAPI>()
    private val extractors = mutableListOf<ExtractorApi>()
    
    fun registerMainAPI(api: MainAPI) {
        mainAPIs.add(api)
    }
    
    fun registerExtractor(extractor: ExtractorApi) {
        extractors.add(extractor)
    }
    
    fun getMainAPIs(): List<MainAPI> = mainAPIs.toList()
    fun getExtractors(): List<ExtractorApi> = extractors.toList()
    
    fun clear() {
        mainAPIs.clear()
        extractors.clear()
    }
}
```

### Step 2.2: Create Extractor API

**File: `app/src/main/java/com/shawken/app/cloudstream/ExtractorApi.kt`**

```kotlin
package com.shawken.app.cloudstream

/**
 * Base class for video extractors
 */
abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    open val requiresReferer: Boolean = false
    
    /**
     * Extract video links from a URL
     */
    abstract suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    )
    
    /**
     * Alternative method that returns links directly
     */
    open suspend fun getUrl(
        url: String,
        referer: String? = null
    ): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        getUrl(url, referer, {}, { links.add(it) })
        return links.ifEmpty { null }
    }
}

/**
 * Helper function to load extractors
 */
suspend fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit = {},
    callback: (ExtractorLink) -> Unit
): Boolean {
    val extractors = CloudStreamPluginRegistry.getExtractors()
    
    for (extractor in extractors) {
        if (url.contains(extractor.mainUrl, ignoreCase = true)) {
            try {
                extractor.getUrl(url, referer, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                // Try next extractor
                continue
            }
        }
    }
    
    return false
}
```

---

## 📦 Phase 3: Extension Manager

### Step 3.1: Update ExtensionManager

**File: `app/src/main/java/com/shawken/app/extensions/UnifiedExtensionManager.kt`**

```kotlin
package com.shawken.app.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.shawken.app.api.Provider
import com.shawken.app.cloudstream.*
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified Extension Manager that supports both Shawken and CloudStream extensions
 */
class UnifiedExtensionManager(private val context: Context) {
    
    private val TAG = "UnifiedExtensionManager"
    private val loadedProviders = mutableMapOf<String, Provider>()
    
    /**
     * Load all extensions (both Shawken and CloudStream)
     */
    suspend fun loadAllExtensions() = withContext(Dispatchers.IO) {
        loadedProviders.clear()
        CloudStreamPluginRegistry.clear()
        
        // Load Shawken extensions (APKs)
        loadShawkenExtensions()
        
        // Load CloudStream extensions (Plugins)
        loadCloudStreamExtensions()
    }
    
    /**
     * Load Shawken-style APK extensions
     */
    private suspend fun loadShawkenExtensions() {
        val packages = context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        
        for (packageInfo in packages) {
            try {
                val metadata = packageInfo.applicationInfo?.metaData ?: continue
                
                // Check if it's a Shawken extension
                if (!metadata.containsKey("provider_class")) continue
                
                val providerClass = metadata.getString("provider_class") ?: continue
                val packageName = packageInfo.packageName
                
                Log.d(TAG, "Loading Shawken extension: $packageName")
                
                val provider = loadShawkenProvider(packageName, providerClass)
                if (provider != null) {
                    loadedProviders[packageName] = provider
                    Log.d(TAG, "✓ Loaded Shawken provider: ${provider.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Shawken extension: ${packageInfo.packageName}", e)
            }
        }
    }
    
    /**
     * Load CloudStream-style plugin extensions
     */
    private suspend fun loadCloudStreamExtensions() {
        val packages = context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        
        for (packageInfo in packages) {
            try {
                val metadata = packageInfo.applicationInfo?.metaData ?: continue
                
                // Check if it's a CloudStream extension
                if (!metadata.containsKey("cloudstream_plugin")) continue
                
                val pluginClass = metadata.getString("cloudstream_plugin") ?: continue
                val packageName = packageInfo.packageName
                
                Log.d(TAG, "Loading CloudStream extension: $packageName")
                
                loadCloudStreamPlugin(packageName, pluginClass)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load CloudStream extension: ${packageInfo.packageName}", e)
            }
        }
        
        // Wrap CloudStream MainAPIs as Shawken Providers
        wrapCloudStreamProviders()
    }
    
    /**
     * Load a Shawken provider from an APK
     */
    private fun loadShawkenProvider(packageName: String, className: String): Provider? {
        return try {
            val packageContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            
            val classLoader = PathClassLoader(
                packageContext.packageCodePath,
                packageContext.applicationInfo.nativeLibraryDir,
                context.classLoader
            )
            
            val providerClass = classLoader.loadClass(className)
            val constructor = providerClass.getConstructor(Context::class.java)
            constructor.newInstance(packageContext) as Provider
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load provider: $className", e)
            null
        }
    }
    
    /**
     * Load a CloudStream plugin from an APK
     */
    private fun loadCloudStreamPlugin(packageName: String, className: String) {
        try {
            val packageContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            
            val classLoader = PathClassLoader(
                packageContext.packageCodePath,
                packageContext.applicationInfo.nativeLibraryDir,
                context.classLoader
            )
            
            val pluginClass = classLoader.loadClass(className)
            
            // Check if it has @CloudstreamPlugin annotation
            if (!pluginClass.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                Log.w(TAG, "Class $className is not annotated with @CloudstreamPlugin")
                return
            }
            
            // Instantiate and load the plugin
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            plugin.load()
            
            Log.d(TAG, "✓ Loaded CloudStream plugin: $className")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load CloudStream plugin: $className", e)
        }
    }
    
    /**
     * Wrap CloudStream MainAPIs as Shawken Providers
     */
    private fun wrapCloudStreamProviders() {
        val mainAPIs = CloudStreamPluginRegistry.getMainAPIs()
        
        for (mainAPI in mainAPIs) {
            try {
                val adapter = CloudStreamAdapter(context, mainAPI)
                val key = "cloudstream_${mainAPI.name}"
                loadedProviders[key] = adapter
                Log.d(TAG, "✓ Wrapped CloudStream provider: ${mainAPI.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to wrap CloudStream provider: ${mainAPI.name}", e)
            }
        }
    }
    
    /**
     * Get all loaded providers
     */
    fun getProviders(): List<Provider> = loadedProviders.values.toList()
    
    /**
     * Get a specific provider by package name
     */
    fun getProvider(packageName: String): Provider? = loadedProviders[packageName]
}
```

---

## 🧪 Phase 4: Testing & Validation

### Step 4.1: Create Test Extension

Create a simple CloudStream extension to test compatibility:

**File: `test-cloudstream-extension/src/main/kotlin/TestPlugin.kt`**

```kotlin
package com.test.cloudstream

import com.shawken.app.cloudstream.*

@CloudstreamPlugin
class TestPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TestProvider())
    }
}

class TestProvider : MainAPI() {
    override var mainUrl = "https://example.com"
    override var name = "Test CloudStream Provider"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf(
            newMovieSearchResponse("Test Movie", "https://example.com/movie/1") {
                this.posterUrl = "https://example.com/poster.jpg"
                this.year = 2024
            }
        )
    }
    
    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            "Test Movie",
            url,
            TvType.Movie,
            "https://example.com/watch/1"
        ) {
            this.posterUrl = "https://example.com/poster.jpg"
            this.plot = "This is a test movie"
            this.year = 2024
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "Test Link",
                url = "https://example.com/video.mp4",
                referer = mainUrl,
                quality = 1080
            )
        )
        return true
    }
}
```

### Step 4.2: AndroidManifest for CloudStream Extension

**File: `test-cloudstream-extension/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <uses-permission android:name="android.permission.INTERNET" />
    
    <application>
        <!-- CloudStream Plugin Metadata -->
        <meta-data
            android:name="cloudstream_plugin"
            android:value="com.test.cloudstream.TestPlugin" />
    </application>
</manifest>
```

---

## 🔄 Phase 5: Migration Path

### For Existing Shawken Extensions

**No changes needed!** They will continue to work as-is.

### For CloudStream Extensions

1. **Install the APK** - CloudStream extensions are Android Libraries, need to be built as APKs
2. **Add metadata** - Add `cloudstream_plugin` metadata to AndroidManifest
3. **That's it!** - The adapter layer handles the rest

### Building CloudStream Extensions as APKs

Modify CloudStream extension `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")  // Change from library to application
    id("kotlin-android")
}

android {
    namespace = "com.cloudstream.extension"
    
    defaultConfig {
        applicationId = "com.cloudstream.extension.unique"  // Add unique app ID
        minSdk = 24
        targetSdk = 35
    }
}
```

---

## ⚠️ Limitations & Considerations

### What Works

✅ Basic search, load, loadLinks flow  
✅ Movies and TV Series  
✅ Custom extractors  
✅ Main page support  
✅ Subtitles  
✅ Quality selection  

### What Needs Adaptation

⚠️ **Advanced Features**:
- CloudStream's built-in extractors (need to port individually)
- CloudStream-specific utilities (need Shawken equivalents)
- Plugin settings/preferences (need custom implementation)

⚠️ **Performance**:
- Adapter layer adds minimal overhead
- Some CloudStream extensions may be slower due to conversion

⚠️ **Maintenance**:
- Need to keep adapter layer updated with CloudStream API changes
- Some CloudStream extensions may use internal APIs not exposed

### Recommended Approach

1. **Start with simple extensions** - Test with basic CloudStream providers first
2. **Port popular extractors** - Implement CloudStream's common extractors in Shawken
3. **Gradual adoption** - Don't force all extensions to migrate
4. **Community feedback** - Let users test and report issues

---

## 📚 Additional Resources

### CloudStream API Documentation
- GitHub: https://github.com/recloudstream/cloudstream
- API Docs: https://recloudstream.github.io/

### Shawken Extension Guide
- See `extensionMakingGuide.md` for Shawken-specific details

### Example Extensions
- CloudStream: Check `cloudstream-extensions-phisher` repo
- Shawken: Check `uhdmovies` extension

---

## 🎯 Next Steps

1. **Implement Phase 1** - Create compatibility layer
2. **Test with one extension** - Use a simple CloudStream extension
3. **Iterate and improve** - Fix issues as they arise
4. **Document findings** - Update this guide with learnings
5. **Release beta** - Let community test
6. **Full rollout** - Make it official

---

**Good luck with the implementation!** 🚀

This approach gives you the best of both worlds - access to CloudStream's massive extension library while maintaining your own Shawken ecosystem.
