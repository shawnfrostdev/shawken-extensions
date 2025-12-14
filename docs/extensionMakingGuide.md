# Shawken Extension Development Guide

> **Complete guide to creating extensions for Shawken streaming app**

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Extension Architecture](#extension-architecture)
4. [Step-by-Step Guide](#step-by-step-guide)
5. [Provider Interface Reference](#provider-interface-reference)
6. [Data Classes Reference](#data-classes-reference)
7. [Example Extension](#example-extension)
8. [Testing Your Extension](#testing-your-extension)
9. [Publishing Your Extension](#publishing-your-extension)
10. [Best Practices](#best-practices)

---

## 🎯 Overview

Shawken extensions are **separate APK files** that provide content to the main app. Each extension implements the `Provider` interface and can search for, load, and extract streaming links from various sources.

### Key Concepts

- **Extension = APK**: Each extension is a standalone Android module compiled to an APK
- **Provider Interface**: The contract that all extensions must implement
- **Dynamic Loading**: Extensions are loaded at runtime via reflection
- **Isolation**: Extensions run in the main app's context but are developed separately

---

## 📦 Prerequisites

Before creating an extension, ensure you have:

- **Android Studio** (latest version)
- **JDK 11** or higher
- **Kotlin** knowledge
- **Understanding of web scraping** (HTML parsing, network requests)
- Access to the **Shawken main app** source code (for the Provider interface)

---

## 🏗️ Extension Architecture

```
YourExtension/
├── build.gradle.kts          # Extension module configuration
├── src/
│   └── main/
│       ├── AndroidManifest.xml    # Extension metadata
│       └── java/
│           └── com/
│               └── yourname/
│                   └── extension/
│                       └── YourProvider.kt  # Provider implementation
```

---

## 📝 Step-by-Step Guide

### Step 1: Create a New Android Module

1. In your Shawken project, go to `File > New > New Module`
2. Select **Android Library**
3. Name: `YourExtensionName` (e.g., `ExampleProvider`)
4. Package name: `com.yourname.extension.yourextension`
5. Click **Finish**

### Step 2: Configure `build.gradle.kts`

Create/modify `YourExtension/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yourname.extension.yourextension"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        
        // Extension metadata
        manifestPlaceholders["providerName"] = "Your Provider Name"
        manifestPlaceholders["providerLang"] = "en"
        manifestPlaceholders["providerVersion"] = "1.0.0"
        manifestPlaceholders["providerClass"] = "com.yourname.extension.yourextension.YourProvider"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Main app dependency (compileOnly - not included in APK)
    compileOnly(project(":app"))
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")
    
    // JSON (optional)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

### Step 3: Configure `AndroidManifest.xml`

Create `YourExtension/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required permissions -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <!-- Extension metadata -->
        <meta-data
            android:name="provider_name"
            android:value="${providerName}" />
        
        <meta-data
            android:name="provider_lang"
            android:value="${providerLang}" />
        
        <meta-data
            android:name="provider_version"
            android:value="${providerVersion}" />
        
        <meta-data
            android:name="provider_class"
            android:value="${providerClass}" />
    </application>
</manifest>
```

### Step 4: Implement the Provider

Create `YourProvider.kt`:

```kotlin
package com.yourname.extension.yourextension

import android.content.Context
import com.shawken.app.api.*
import org.jsoup.Jsoup
import okhttp3.OkHttpClient

class YourProvider(context: Context) : Provider(context) {

    // Required metadata
    override val name = "Your Provider Name"
    override val lang = "en"
    override val baseUrl = "https://example.com"
    override val description = "Description of your provider"
    override val iconUrl = "https://example.com/icon.png"

    // Capabilities
    override val supportsSearch = true
    override val supportsLatest = true
    override val supportsTrending = false

    // HTTP client for making requests
    private val client = OkHttpClient()

    /**
     * Search for content
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Map<String, String>
    ): SearchResponse {
        // Build search URL
        val searchUrl = "$baseUrl/search?q=$query&page=$page"
        
        // Make HTTP request
        val response = client.newCall(
            okhttp3.Request.Builder()
                .url(searchUrl)
                .build()
        ).execute()
        
        // Parse HTML
        val document = Jsoup.parse(response.body?.string() ?: "")
        
        // Extract search results
        val results = document.select(".search-result").map { element ->
            SearchResult(
                name = element.select(".title").text(),
                url = element.select("a").attr("href"),
                posterUrl = element.select("img").attr("src"),
                type = ContentType.MOVIE, // or TV_SERIES, ANIME, etc.
                quality = element.select(".quality").text(),
                year = element.select(".year").text().toIntOrNull()
            )
        }
        
        // Check if there's a next page
        val hasNextPage = document.select(".next-page").isNotEmpty()
        
        return SearchResponse(
            results = results,
            hasNextPage = hasNextPage
        )
    }

    /**
     * Load detailed information about a content item
     */
    override suspend fun load(url: String): LoadResponse {
        // Make HTTP request
        val response = client.newCall(
            okhttp3.Request.Builder()
                .url(url)
                .build()
        ).execute()
        
        // Parse HTML
        val document = Jsoup.parse(response.body?.string() ?: "")
        
        // Determine if it's a movie or TV series
        val isMovie = document.select(".movie-indicator").isNotEmpty()
        
        return if (isMovie) {
            // Movie
            MovieLoadResponse(
                name = document.select(".title").text(),
                url = url,
                dataUrl = document.select(".watch-button").attr("data-url"),
                posterUrl = document.select(".poster img").attr("src"),
                year = document.select(".year").text().toIntOrNull(),
                plot = document.select(".plot").text(),
                rating = document.select(".rating").text().toFloatOrNull(),
                tags = document.select(".genre").map { it.text() },
                duration = document.select(".duration").text().toIntOrNull()
            )
        } else {
            // TV Series
            val episodes = document.select(".episode").map { ep ->
                Episode(
                    name = ep.select(".ep-title").text(),
                    season = ep.attr("data-season").toInt(),
                    episode = ep.attr("data-episode").toInt(),
                    dataUrl = ep.select("a").attr("href"),
                    posterUrl = ep.select("img").attr("src"),
                    description = ep.select(".description").text()
                )
            }
            
            TvSeriesLoadResponse(
                name = document.select(".title").text(),
                url = url,
                posterUrl = document.select(".poster img").attr("src"),
                year = document.select(".year").text().toIntOrNull(),
                plot = document.select(".plot").text(),
                rating = document.select(".rating").text().toFloatOrNull(),
                tags = document.select(".genre").map { it.text() },
                episodes = episodes
            )
        }
    }

    /**
     * Extract streaming links from a data URL
     */
    override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
        // Make HTTP request to get the video page
        val response = client.newCall(
            okhttp3.Request.Builder()
                .url(dataUrl)
                .build()
        ).execute()
        
        // Parse HTML
        val document = Jsoup.parse(response.body?.string() ?: "")
        
        // Extract video links
        val links = mutableListOf<ExtractorLink>()
        
        // Example: Direct MP4 links
        document.select(".video-source").forEach { source ->
            val videoUrl = source.attr("src")
            val quality = source.attr("data-quality").toIntOrNull() ?: 0
            
            links.add(
                ExtractorLink(
                    source = name,
                    name = "$name - ${quality}p",
                    url = videoUrl,
                    referer = baseUrl,
                    quality = quality,
                    isM3u8 = videoUrl.contains(".m3u8"),
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to baseUrl
                    )
                )
            )
        }
        
        return links
    }

    /**
     * Get main page content (optional)
     */
    override suspend fun getMainPage(): HomePageResponse {
        val response = client.newCall(
            okhttp3.Request.Builder()
                .url(baseUrl)
                .build()
        ).execute()
        
        val document = Jsoup.parse(response.body?.string() ?: "")
        
        val sections = mutableListOf<HomePageList>()
        
        // Trending section
        val trending = document.select(".trending .item").map { item ->
            SearchResult(
                name = item.select(".title").text(),
                url = item.select("a").attr("href"),
                posterUrl = item.select("img").attr("src"),
                type = ContentType.MOVIE
            )
        }
        
        if (trending.isNotEmpty()) {
            sections.add(HomePageList("Trending", trending))
        }
        
        return HomePageResponse(sections)
    }
}
```

### Step 5: Add Extension to Settings

Add your extension module to `settings.gradle.kts`:

```kotlin
include(":app")
include(":YourExtensionName")  // Add this line
```

### Step 6: Build the Extension APK

Run in terminal:

```bash
./gradlew :YourExtensionName:assembleRelease
```

The APK will be generated at:
```
YourExtensionName/build/outputs/apk/release/YourExtensionName-release.apk
```

---

## 📚 Provider Interface Reference

### Required Properties

```kotlin
abstract val name: String              // Provider name (e.g., "Example Provider")
abstract val lang: String              // Language code (e.g., "en", "es")
abstract val baseUrl: String           // Base URL of the source
open val description: String = ""      // Provider description
open val iconUrl: String = ""          // Icon URL
```

### Capability Flags

```kotlin
open val supportsSearch: Boolean = true      // Can search
open val supportsLatest: Boolean = false     // Has latest content
open val supportsTrending: Boolean = false   // Has trending content
```

### Required Methods

```kotlin
// Search for content
abstract suspend fun search(
    query: String,
    page: Int = 1,
    filters: Map<String, String> = emptyMap()
): SearchResponse

// Load detailed information
abstract suspend fun load(url: String): LoadResponse

// Extract streaming links
abstract suspend fun loadLinks(dataUrl: String): List<ExtractorLink>
```

### Optional Methods

```kotlin
// Get main page content
open suspend fun getMainPage(): HomePageResponse

// Get available filters
open suspend fun getFilters(): List<Filter>
```

---

## 📦 Data Classes Reference

### SearchResponse

```kotlin
data class SearchResponse(
    val results: List<SearchResult>,
    val hasNextPage: Boolean = false
)
```

### SearchResult

```kotlin
data class SearchResult(
    val name: String,              // Title
    val url: String,               // URL to load details
    val posterUrl: String? = null, // Poster image URL
    val type: ContentType,         // MOVIE, TV_SERIES, ANIME, etc.
    val quality: String? = null,   // Quality badge (e.g., "HD", "4K")
    val year: Int? = null          // Release year
)
```

### ContentType

```kotlin
enum class ContentType {
    MOVIE,
    TV_SERIES,
    ANIME,
    DOCUMENTARY,
    OTHER
}
```

### LoadResponse (Sealed Class)

#### MovieLoadResponse

```kotlin
data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    val dataUrl: String,                    // URL to extract links from
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Float? = null,     // 0-10
    override val tags: List<String> = emptyList(),
    val duration: Int? = null,              // Duration in minutes
    val recommendations: List<SearchResult> = emptyList()
) : LoadResponse()
```

#### TvSeriesLoadResponse

```kotlin
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
```

### Episode

```kotlin
data class Episode(
    val name: String,              // Episode title
    val season: Int,               // Season number
    val episode: Int,              // Episode number
    val dataUrl: String,           // URL to extract links from
    val posterUrl: String? = null, // Episode thumbnail
    val description: String? = null,
    val date: String? = null       // Air date
)
```

### ExtractorLink

```kotlin
data class ExtractorLink(
    val source: String,                    // Source name
    val name: String,                      // Display name
    val url: String,                       // Streaming URL
    val referer: String = "",              // Referer header
    val quality: Int = 0,                  // Quality (1080, 720, etc.)
    val isM3u8: Boolean = false,           // Is HLS stream
    val headers: Map<String, String> = emptyMap()
)
```

### SubtitleFile

```kotlin
data class SubtitleFile(
    val url: String,
    val lang: String,                      // Language code
    val format: SubtitleFormat = SubtitleFormat.SRT
)

enum class SubtitleFormat {
    SRT, VTT, ASS
}
```

### HomePageResponse

```kotlin
data class HomePageResponse(
    val items: List<HomePageList>
)

data class HomePageList(
    val name: String,              // Section name (e.g., "Trending")
    val list: List<SearchResult>
)
```

### Filter (Sealed Class)

```kotlin
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
```

---

## 💡 Example Extension

Here's a complete minimal example:

```kotlin
package com.example.testprovider

import android.content.Context
import com.shawken.app.api.*

class TestProvider(context: Context) : Provider(context) {

    override val name = "Test Provider"
    override val lang = "en"
    override val baseUrl = "https://test.com"

    override suspend fun search(
        query: String,
        page: Int,
        filters: Map<String, String>
    ): SearchResponse {
        // Return hardcoded test data
        return SearchResponse(
            results = listOf(
                SearchResult(
                    name = "Test Movie",
                    url = "$baseUrl/movie/1",
                    posterUrl = "https://via.placeholder.com/300x450",
                    type = ContentType.MOVIE,
                    quality = "HD",
                    year = 2024
                )
            ),
            hasNextPage = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        return MovieLoadResponse(
            name = "Test Movie",
            url = url,
            dataUrl = "$baseUrl/watch/1",
            posterUrl = "https://via.placeholder.com/300x450",
            year = 2024,
            plot = "This is a test movie",
            rating = 8.5f,
            tags = listOf("Action", "Adventure"),
            duration = 120
        )
    }

    override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
        return listOf(
            ExtractorLink(
                source = name,
                name = "$name - 1080p",
                url = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4",
                quality = 1080,
                isM3u8 = false
            )
        )
    }
}
```

---

## 🧪 Testing Your Extension

### 1. Local Testing

Build and install your extension APK:

```bash
./gradlew :YourExtension:assembleDebug
adb install YourExtension/build/outputs/apk/debug/YourExtension-debug.apk
```

### 2. In-App Testing

1. Open Shawken app
2. Go to **Extensions** screen
3. Tap **Install from file**
4. Select your extension APK
5. Verify it appears in the installed list
6. Test search, load, and playback functionality

### 3. Debugging

Add logging to your extension:

```kotlin
import android.util.Log

class YourProvider(context: Context) : Provider(context) {
    private val TAG = "YourProvider"
    
    override suspend fun search(...): SearchResponse {
        Log.d(TAG, "Searching for: $query")
        // ... implementation
    }
}
```

View logs:
```bash
adb logcat | grep YourProvider
```

---

## 📤 Publishing Your Extension

### Option 1: GitHub Releases

1. Create a GitHub repository for your extension
2. Build release APK
3. Create a new release
4. Upload the APK
5. Share the download link

### Option 2: Extension Repository

Create a `repo.json` file:

```json
{
  "name": "Your Extension Repository",
  "description": "Collection of extensions",
  "author": "Your Name",
  "url": "https://yoursite.com/repo.json",
  "version": "1.0.0",
  "extensions": [
    {
      "name": "Your Provider",
      "description": "Description",
      "version": "1.0.0",
      "versionName": "1.0.0",
      "lang": "en",
      "apkUrl": "https://yoursite.com/extensions/yourprovider-v1.0.0.apk",
      "iconUrl": "https://yoursite.com/icons/yourprovider.png",
      "sha256": "hash_of_your_apk",
      "minAppVersion": "1.0.0",
      "sources": ["https://example.com"]
    }
  ]
}
```

Host this JSON file and share the URL with users.

---

## ✅ Best Practices

### 1. Error Handling

Always wrap network calls in try-catch:

```kotlin
override suspend fun search(...): SearchResponse {
    return try {
        // Your implementation
    } catch (e: Exception) {
        Log.e(TAG, "Search failed", e)
        SearchResponse(emptyList(), false)
    }
}
```

### 2. Respect Rate Limits

Add delays between requests if needed:

```kotlin
import kotlinx.coroutines.delay

override suspend fun search(...): SearchResponse {
    delay(500) // 500ms delay
    // ... implementation
}
```

### 3. User-Agent Headers

Always set a proper User-Agent:

```kotlin
private val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        chain.proceed(request)
    }
    .build()
```

### 4. Cache Responses

Consider caching to reduce server load:

```kotlin
private val cache = mutableMapOf<String, SearchResponse>()

override suspend fun search(...): SearchResponse {
    val cacheKey = "$query-$page"
    return cache.getOrPut(cacheKey) {
        // Fetch from network
    }
}
```

### 5. Handle Pagination

Properly detect and handle pagination:

```kotlin
val hasNextPage = document.select(".pagination .next").isNotEmpty()
```

### 6. Quality Detection

Try to extract quality information:

```kotlin
val quality = when {
    title.contains("4K", ignoreCase = true) -> "4K"
    title.contains("1080p", ignoreCase = true) -> "1080p"
    title.contains("720p", ignoreCase = true) -> "720p"
    else -> null
}
```

### 7. Subtitle Support

If the source provides subtitles, include them:

```kotlin
// In loadLinks method
val subtitles = document.select(".subtitle-track").map { track ->
    SubtitleFile(
        url = track.attr("src"),
        lang = track.attr("srclang"),
        format = SubtitleFormat.VTT
    )
}
```

---

## 🔧 Troubleshooting

### Extension Not Loading

- Check AndroidManifest.xml metadata
- Verify provider_class points to correct class
- Ensure class extends Provider
- Check logs for errors

### No Search Results

- Verify HTML selectors are correct
- Check network connectivity
- Add logging to debug
- Test URL in browser first

### Video Not Playing

- Verify ExtractorLink URL is valid
- Check if URL requires headers/referer
- Test if it's M3U8 (set isM3u8 = true)
- Verify quality value is set

### Build Errors

- Ensure `compileOnly(project(":app"))` is in dependencies
- Check Kotlin version compatibility
- Sync Gradle files
- Clean and rebuild project

---

## 📞 Support

- **GitHub Issues**: Report bugs or request features
- **Discord**: Join the community for help
- **Documentation**: Check the main README

---

## 📄 License

Extensions should be open source and follow the same license as the main app.

---

**Happy Extension Development! 🚀**
