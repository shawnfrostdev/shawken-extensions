# Shawken Extension Development Guide

> **Complete reference for creating extensions for Shawken - covers everything from setup to publishing**

## 📋 Table of Contents

1. [Overview](#overview)
2. [Architecture & How Extensions Work](#architecture--how-extensions-work)
3. [Prerequisites](#prerequisites)
4. [Quick Start Guide](#quick-start-guide)
5. [Complete API Reference](#complete-api-reference)
6. [Main App Integration](#main-app-integration)
7. [Advanced Topics](#advanced-topics)
8. [Testing & Debugging](#testing--debugging)
9. [Publishing](#publishing)
10. [Troubleshooting](#troubleshooting)

---

## 🎯 Overview

Shawken extensions are **standalone Android APK files** that provide streaming content to the main app. Each extension:

- Is a separate Android application module
- Implements the `Provider` abstract class from `extension-api`
- Gets loaded dynamically at runtime by the main app
- Can scrape and extract content from any streaming source
- Runs in the main app's context with full Android API access

**Key Point**: Extensions are NOT plugins - they're full Android apps that the main app loads via `PackageManager` and reflection.

---

## 🏗️ Architecture & How Extensions Work

### Extension Loading Flow

```
1. User installs extension APK (separate from main app)
2. Main app scans installed packages for extension metadata
3. ExtensionManager finds packages with "provider_class" meta-data
4. Main app creates PackageContext for the extension
5. Uses PathClassLoader to load the Provider class
6. Instantiates Provider with extension's Context
7. Provider is now available for search/load/loadLinks operations
```

### Project Structure

```
shawken/
├── app/                          # Main application
│   ├── extensions/
│   │   ├── ExtensionManager.kt   # Loads & manages extensions
│   │   ├── ExtensionInfo.kt      # Repository extension metadata
│   │   ├── Repository.kt         # Repository JSON structure
│   │   └── RepositoryManager.kt  # Downloads extensions from repos
│   └── presentation/
│       └── extensions/           # Extensions UI
├── extension-api/                # Shared API module
│   └── src/main/java/com/shawken/app/api/
│       └── Provider.kt           # Base class + all data classes
└── extensions/                   # Your extensions go here
    └── YourExtension/
        ├── build.gradle.kts
        ├── src/main/
        │   ├── AndroidManifest.xml
        │   └── java/.../YourProvider.kt
```

### Critical Files

**extension-api/Provider.kt** - Contains:
- `abstract class Provider` - Base class for all extensions
- All data classes: `SearchResponse`, `SearchResult`, `LoadResponse`, `ExtractorLink`, etc.
- All enums: `ContentType`, `SubtitleFormat`
- All sealed classes: `Filter`

**app/extensions/ExtensionManager.kt** - Handles:
- Scanning for installed extension packages
- Loading Provider classes via reflection
- Managing loaded providers in memory
- Package installation verification

---

## 📦 Prerequisites

### Required

- **Android Studio** Arctic Fox or newer
- **JDK 11** or higher
- **Kotlin** 1.9.20+
- **Gradle** 8.0+
- Access to Shawken source code (for `extension-api` module)

### Knowledge Required

- Kotlin coroutines (all Provider methods are `suspend fun`)
- HTML parsing (Jsoup)
- HTTP networking (OkHttp)
- Android Context and PackageManager basics

---

## 🚀 Quick Start Guide

### Step 1: Create Extension Module

**Option A: Using Android Studio**
1. File → New → New Module
2. Select "Android Application" (NOT Library - extensions are apps!)
3. Name: `YourExtensionName`
4. Package: `com.yourname.extensions.yourextension`
5. Minimum SDK: 24

**Option B: Manual Setup**
Create folder: `extensions/YourExtension/`

### Step 2: Configure build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.android.application)  // APPLICATION, not library!
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yourname.extensions.yourextension"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yourname.extensions.yourextension"  // MUST be unique
        minSdk = 24
        
        // ⚠️ CRITICAL: These placeholders are used in AndroidManifest.xml
        manifestPlaceholders["providerName"] = "Your Provider Name"
        manifestPlaceholders["providerLang"] = "en"  // ISO 639-1 code
        manifestPlaceholders["providerVersion"] = "1"  // Integer version code
        manifestPlaceholders["providerClass"] = "com.yourname.extensions.yourextension.YourProvider"
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // Keep false for compatibility
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
    // ⚠️ CRITICAL: Use compileOnly - main app provides these at runtime
    compileOnly(project(":extension-api"))
    
    // Your extension's dependencies (these WILL be included in APK)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### Step 3: Create AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required permission -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <!-- ⚠️ CRITICAL: Main app scans for these meta-data tags -->
        <meta-data
            android:name="provider_name"
            android:value="${providerName}" />
        
        <meta-data
            android:name="provider_lang"
            android:value="${providerLang}" />
        
        <meta-data
            android:name="provider_version"
            android:value="${providerVersion}" />
        
        <!-- ⚠️ CRITICAL: Fully qualified class name -->
        <meta-data
            android:name="provider_class"
            android:value="${providerClass}" />
    </application>
</manifest>
```

### Step 4: Implement Provider Class

```kotlin
package com.yourname.extensions.yourextension

import android.content.Context
import com.shawken.app.api.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class YourProvider(context: Context) : Provider(context) {

    // ⚠️ REQUIRED: Provider metadata
    override val name = "Your Provider Name"
    override val lang = "en"
    override val baseUrl = "https://example.com"
    override val description = "Streams from example.com"
    override val iconUrl = "https://example.com/icon.png"  // Optional

    // ⚠️ REQUIRED: Capability flags
    override val supportsSearch = true
    override val supportsLatest = false
    override val supportsTrending = false

    private val client = OkHttpClient()

    // ⚠️ REQUIRED: Search implementation
    override suspend fun search(
        query: String,
        page: Int,
        filters: Map<String, String>
    ): SearchResponse {
        val url = "$baseUrl/search?q=$query&page=$page"
        val html = client.newCall(Request.Builder().url(url).build())
            .execute().body?.string() ?: ""
        
        val doc = Jsoup.parse(html)
        val results = doc.select(".result-item").map { el ->
            SearchResult(
                name = el.select(".title").text(),
                url = el.select("a").attr("abs:href"),
                posterUrl = el.select("img").attr("abs:src"),
                type = ContentType.MOVIE,
                quality = el.select(".badge").text(),
                year = el.select(".year").text().toIntOrNull()
            )
        }
        
        return SearchResponse(
            results = results,
            hasNextPage = doc.select(".next-page").isNotEmpty()
        )
    }

    // ⚠️ REQUIRED: Load details implementation
    override suspend fun load(url: String): LoadResponse {
        val html = client.newCall(Request.Builder().url(url).build())
            .execute().body?.string() ?: ""
        val doc = Jsoup.parse(html)
        
        // Example for movie
        return MovieLoadResponse(
            name = doc.select("h1.title").text(),
            url = url,
            dataUrl = doc.select("#watch-button").attr("data-url"),
            posterUrl = doc.select(".poster").attr("abs:src"),
            year = doc.select(".year").text().toIntOrNull(),
            plot = doc.select(".synopsis").text(),
            rating = doc.select(".rating").text().toFloatOrNull(),
            tags = doc.select(".genre").map { it.text() },
            duration = doc.select(".duration").text().toIntOrNull()
        )
    }

    // ⚠️ REQUIRED: Extract streaming links
    override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
        val html = client.newCall(Request.Builder().url(dataUrl).build())
            .execute().body?.string() ?: ""
        val doc = Jsoup.parse(html)
        
        return doc.select("source[src]").map { source ->
            ExtractorLink(
                source = name,
                name = "$name - ${source.attr("data-quality")}",
                url = source.attr("abs:src"),
                referer = baseUrl,
                quality = source.attr("data-quality").toIntOrNull() ?: 0,
                isM3u8 = source.attr("src").contains(".m3u8"),
                headers = mapOf("Referer" to baseUrl)
            )
        }
    }
}
```

### Step 5: Add to settings.gradle.kts

```kotlin
include(":app")
include(":extension-api")
include(":extensions:YourExtension")  // Add this
```

### Step 6: Build Extension APK

```bash
./gradlew :extensions:YourExtension:assembleRelease
```

APK location: `extensions/YourExtension/build/outputs/apk/release/YourExtension-release.apk`

---

## 📚 Complete API Reference

### Provider Abstract Class

**Location**: `extension-api/src/main/java/com/shawken/app/api/Provider.kt`

```kotlin
abstract class Provider(val context: Context) {
    // Required properties
    abstract val name: String
    abstract val lang: String
    abstract val baseUrl: String
    
    // Optional properties
    open val description: String = ""
    open val iconUrl: String = ""
    open val supportsSearch: Boolean = true
    open val supportsLatest: Boolean = false
    open val supportsTrending: Boolean = false
    
    // Required methods
    abstract suspend fun search(query: String, page: Int = 1, filters: Map<String, String> = emptyMap()): SearchResponse
    abstract suspend fun load(url: String): LoadResponse
    abstract suspend fun loadLinks(dataUrl: String): List<ExtractorLink>
    
    // Optional methods
    open suspend fun getMainPage(): HomePageResponse = HomePageResponse(emptyList())
    open suspend fun getFilters(): List<Filter> = emptyList()
    
    // ⚠️ IMPORTANT: TMDB integration method
    open suspend fun searchByTitleYear(title: String, year: Int?, type: ContentType): SearchResult? {
        // Default implementation searches and matches by year ±2
        // Override for better accuracy
    }
}
```

### Data Classes

**SearchResponse**
```kotlin
data class SearchResponse(
    val results: List<SearchResult>,
    val hasNextPage: Boolean = false
)
```

**SearchResult**
```kotlin
data class SearchResult(
    val name: String,              // Required
    val url: String,               // Required - URL to pass to load()
    val posterUrl: String? = null,
    val type: ContentType = ContentType.OTHER,
    val quality: String? = null,   // e.g., "HD", "CAM", "4K"
    val year: Int? = null
)
```

**ContentType enum**
```kotlin
enum class ContentType {
    MOVIE,
    TV_SERIES,
    ANIME,
    DOCUMENTARY,
    OTHER
}
```

**LoadResponse (sealed class)**

Base class:
```kotlin
sealed class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val posterUrl: String?
    abstract val year: Int?
    abstract val plot: String?
    abstract val rating: Float?  // 0.0 to 10.0
    abstract val tags: List<String>
    abstract val type: ContentType
}
```

Movie variant:
```kotlin
data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    val dataUrl: String,  // ⚠️ CRITICAL: URL to pass to loadLinks()
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Float? = null,
    override val tags: List<String> = emptyList(),
    val duration: Int? = null,  // Minutes
    val recommendations: List<SearchResult> = emptyList()
) : LoadResponse() {
    override val type = ContentType.MOVIE
}
```

TV Series variant:
```kotlin
data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Float? = null,
    override val tags: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),  // ⚠️ CRITICAL
    val recommendations: List<SearchResult> = emptyList()
) : LoadResponse() {
    override val type = ContentType.TV_SERIES
}
```

**Episode**
```kotlin
data class Episode(
    val name: String,
    val season: Int,
    val episode: Int,
    val dataUrl: String,  // ⚠️ CRITICAL: URL to pass to loadLinks()
    val posterUrl: String? = null,
    val description: String? = null,
    val date: String? = null  // Air date
)
```

**ExtractorLink**
```kotlin
data class ExtractorLink(
    val source: String,      // Server name
    val name: String,        // Display name
    val url: String,         // ⚠️ CRITICAL: Actual video URL
    val referer: String = "",
    val quality: Int = 0,    // 1080, 720, 480, etc.
    val isM3u8: Boolean = false,  // ⚠️ Set true for HLS streams
    val headers: Map<String, String> = emptyMap()
)
```

**SubtitleFile**
```kotlin
data class SubtitleFile(
    val url: String,
    val lang: String,  // ISO 639-1 code
    val format: SubtitleFormat = SubtitleFormat.SRT
)

enum class SubtitleFormat { SRT, VTT, ASS }
```

**HomePageResponse**
```kotlin
data class HomePageResponse(
    val items: List<HomePageList>
)

data class HomePageList(
    val name: String,  // Section title
    val list: List<SearchResult>
)
```

**Filter (sealed class)**
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

## 🔗 Main App Integration

### How Main App Finds Extensions

**ExtensionManager.findInstalledExtensionPackages()**
```kotlin
// Scans all installed packages
val installedPackages = context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)

// Filters for packages with "provider_class" metadata
for (pkgInfo in installedPackages) {
    val metadata = pkgInfo.applicationInfo?.metaData
    if (metadata?.containsKey("provider_class") == true) {
        // Found an extension!
    }
}
```

### How Main App Loads Extensions

**ExtensionManager.loadProvider(packageName)**
```kotlin
// 1. Get package info
val pkgInfo = context.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
val providerClass = pkgInfo.applicationInfo.metaData.getString("provider_class")

// 2. Create package context
val extensionContext = context.createPackageContext(
    packageName,
    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
)

// 3. Load class with custom ClassLoader
val classLoader = PathClassLoader(
    extensionContext.packageCodePath,
    extensionContext.applicationInfo.nativeLibraryDir,
    context.classLoader  // ⚠️ Parent is main app's ClassLoader
)

val loadedClass = classLoader.loadClass(providerClass)
val constructor = loadedClass.getConstructor(Context::class.java)
val provider = constructor.newInstance(extensionContext) as Provider
```

### How Main App Uses Extensions

**Search Flow**:
```kotlin
// User searches for "Stranger Things"
val providers = extensionManager.getProviders()
providers.forEach { provider ->
    val results = provider.search("Stranger Things", page = 1)
    // Display results in UI
}
```

**Details Flow**:
```kotlin
// User clicks on a search result
val loadResponse = provider.load(searchResult.url)
when (loadResponse) {
    is MovieLoadResponse -> {
        // Show movie details
        // dataUrl is stored for playback
    }
    is TvSeriesLoadResponse -> {
        // Show episode list
        // Each episode.dataUrl is stored
    }
}
```

**Playback Flow**:
```kotlin
// User clicks play
val links = provider.loadLinks(dataUrl)  // dataUrl from MovieLoadResponse or Episode
val bestLink = links.maxByOrNull { it.quality }
// Pass link.url to ExoPlayer
```

### TMDB Integration

Main app uses TMDB for metadata, then calls:
```kotlin
val searchResult = provider.searchByTitleYear(
    title = "Stranger Things",
    year = 2016,
    type = ContentType.TV_SERIES
)
```

Default implementation in Provider does fuzzy matching (±2 years). Override for better accuracy.

---

## 🎓 Advanced Topics

### Error Handling

Always wrap in try-catch:
```kotlin
override suspend fun search(...): SearchResponse {
    return try {
        // Implementation
    } catch (e: Exception) {
        Log.e("YourProvider", "Search failed", e)
        SearchResponse(emptyList(), false)
    }
}
```

### Custom Headers & Cookies

```kotlin
private val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0...")
            .header("Referer", baseUrl)
            .build()
        chain.proceed(request)
    }
    .cookieJar(/* your cookie jar */)
    .build()
```

### Handling Pagination

```kotlin
override suspend fun search(query: String, page: Int, ...): SearchResponse {
    val url = "$baseUrl/search?q=$query&page=$page"
    // ...
    val hasNextPage = doc.select(".pagination .next").isNotEmpty()
    return SearchResponse(results, hasNextPage)
}
```

### TV Series with Multiple Seasons

```kotlin
val episodes = mutableListOf<Episode>()
doc.select(".season").forEach { seasonEl ->
    val seasonNum = seasonEl.attr("data-season").toInt()
    seasonEl.select(".episode").forEach { epEl ->
        episodes.add(Episode(
            name = epEl.select(".title").text(),
            season = seasonNum,
            episode = epEl.attr("data-episode").toInt(),
            dataUrl = epEl.select("a").attr("abs:href")
        ))
    }
}
```

### M3U8 / HLS Streams

```kotlin
ExtractorLink(
    source = name,
    name = "$name - Auto",
    url = "https://example.com/playlist.m3u8",
    isM3u8 = true,  // ⚠️ CRITICAL: Tells player to use HLS
    quality = 0  // Auto quality
)
```

### Subtitles

```kotlin
// In loadLinks, also return subtitles if available
val subtitles = doc.select("track[kind=subtitles]").map {
    SubtitleFile(
        url = it.attr("abs:src"),
        lang = it.attr("srclang"),
        format = SubtitleFormat.VTT
    )
}
// Note: Current API doesn't return subtitles from loadLinks
// You may need to add them to ExtractorLink or extend the API
```

---

## 🧪 Testing & Debugging

### Local Testing

```bash
# Build debug APK
./gradlew :extensions:YourExtension:assembleDebug

# Install on device/emulator
adb install extensions/YourExtension/build/outputs/apk/debug/YourExtension-debug.apk

# View logs
adb logcat | grep "YourProvider"
```

### In-App Testing

1. Open Shawken app
2. Navigate to Extensions screen
3. Your extension should appear in "Installed" tab
4. Try searching
5. Try loading details
6. Try playing a video

### Common Issues

**Extension not appearing**:
- Check AndroidManifest.xml has all 4 meta-data tags
- Verify `provider_class` is fully qualified name
- Check `adb logcat` for errors

**ClassCastException**:
- Ensure you're extending `Provider` from `com.shawken.app.api`
- Check that `compileOnly(project(":extension-api"))` is in build.gradle

**No search results**:
- Add logging to see what HTML you're getting
- Test selectors in browser DevTools first
- Check if site requires headers/cookies

---

## 📤 Publishing

### Option 1: Direct APK Distribution

1. Build release APK:
```bash
./gradlew :extensions:YourExtension:assembleRelease
```

2. Sign APK (required for distribution):
```bash
# Generate keystore (once)
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias

# Sign APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore my-release-key.jks app-release-unsigned.apk my-key-alias
```

3. Upload to GitHub Releases or your website

### Option 2: Extension Repository

Create `repo.json`:
```json
{
  "name": "My Extension Repository",
  "description": "Collection of streaming extensions",
  "author": "Your Name",
  "url": "https://yoursite.com/repo.json",
  "version": 1,
  "extensions": [
    {
      "name": "Your Provider",
      "description": "Streams from example.com",
      "version": 1,
      "versionName": "1.0.0",
      "lang": "en",
      "apkUrl": "https://yoursite.com/extensions/yourprovider-v1.0.0.apk",
      "iconUrl": "https://yoursite.com/icons/yourprovider.png",
      "sha256": "abc123...",  // SHA-256 hash of APK
      "minAppVersion": 1,
      "sources": ["https://example.com"]
    }
  ]
}
```

Calculate SHA-256:
```bash
sha256sum yourprovider-v1.0.0.apk
```

Users add your repo URL in Shawken's Extensions screen.

---

## 🔧 Troubleshooting

### Build Errors

**"Cannot resolve symbol Provider"**
- Add `compileOnly(project(":extension-api"))` to dependencies
- Sync Gradle

**"Duplicate class found"**
- Make sure you're using `compileOnly` not `implementation` for extension-api

### Runtime Errors

**Extension loads but crashes on search**
- Check network permissions in AndroidManifest
- Wrap code in try-catch
- Check logs for stack trace

**"No extensions installed" but APK is installed**
- Verify all 4 meta-data tags in AndroidManifest
- Check `provider_class` matches actual class name
- Reinstall extension

**Video won't play**
- Verify `ExtractorLink.url` is valid (test in browser)
- Check if `isM3u8` flag is correct
- Verify headers/referer are set if required
- Check if URL needs to be decoded

---

## 📞 Support & Resources

- **Source Code**: Check `extension-api/Provider.kt` for latest API
- **Example**: See `extensions/TestProvider/` for working example
- **Main App Code**: `app/extensions/ExtensionManager.kt` shows how extensions are loaded

---

**Happy Extension Development! 🚀**

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
