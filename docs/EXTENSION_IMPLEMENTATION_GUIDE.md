# Shawken Extension Implementation Guide

## 📚 Table of Contents
1. [Overview](#overview)
2. [Extension Architecture](#extension-architecture)
3. [Creating a New Extension](#creating-a-new-extension)
4. [Provider Implementation](#provider-implementation)
5. [Repository Configuration](#repository-configuration)
6. [Building and Deployment](#building-and-deployment)
7. [Troubleshooting](#troubleshooting)

---

## Overview

Shawken extensions are Android APK files that implement the `Provider` interface to supply streaming content. Each extension can search for content, load metadata, and provide streaming links.

### Key Concepts
- **Provider**: The main class that implements content discovery and streaming
- **Repository**: A JSON file that lists all available extensions
- **TMDB Integration**: Most extensions use The Movie Database (TMDB) API for metadata
- **Streaming Sources**: External services that provide actual video streams

---

## Extension Architecture

### Directory Structure
```
extension-name/
├── app/
│   ├── build.gradle
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/com/shawken/
│               ├── app/api/
│               │   ├── Models.kt
│               │   └── Provider.kt
│               └── extensionname/
│                   └── ExtensionProvider.kt
├── build.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── extension-name.apk (generated)
```

### Core Files

#### 1. `build.gradle` (Project Level)
```gradle
buildscript {
    ext.kotlin_version = "1.9.0"
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath "com.android.tools.build:gradle:8.1.0"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

#### 2. `app/build.gradle`
```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.shawken.extensionname'
    compileSdk 34

    defaultConfig {
        applicationId "com.shawken.extensionname"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'com.squareup.okhttp3:okhttp:4.11.0'
    implementation 'org.json:json:20230227'
}
```

#### 3. `AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true">
        
        <meta-data
            android:name="shawken.extension"
            android:value="true" />
        
        <meta-data
            android:name="shawken.extension.class"
            android:value="com.shawken.extensionname.ExtensionProvider" />
    </application>
</manifest>
```

---

## Creating a New Extension

### Step 1: Copy Template
```bash
# Copy an existing extension as a template
cp -r xprime/ mynewextension/
cd mynewextension/
```

### Step 2: Update Package Names
1. Rename directories:
   - `app/src/main/java/com/shawken/xprime/` → `app/src/main/java/com/shawken/mynewextension/`

2. Update `build.gradle`:
   ```gradle
   namespace 'com.shawken.mynewextension'
   applicationId "com.shawken.mynewextension"
   ```

3. Update `AndroidManifest.xml`:
   ```xml
   <meta-data
       android:name="shawken.extension.class"
       android:value="com.shawken.mynewextension.MyNewExtensionProvider" />
   ```

### Step 3: Implement Provider
Create `MyNewExtensionProvider.kt`:

```kotlin
package com.shawken.mynewextension

import android.content.Context
import com.shawken.app.api.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MyNewExtensionProvider(context: Context) : Provider(context) {
    
    override val name = "My Extension"
    override val lang = "en"
    override val baseUrl = "https://example.com"
    override val description = "Description of my extension"
    override val iconUrl = "https://example.com/icon.png"
    
    override val supportsSearch = true
    override val supportsLatest = true
    override val supportsTrending = true
    
    private val client = OkHttpClient()
    
    override suspend fun search(query: String, page: Int, filters: Map<String, String>): SearchResponse {
        // Implement search logic
        return SearchResponse(emptyList())
    }
    
    override suspend fun load(url: String): LoadResponse {
        // Implement content loading logic
        return MovieLoadResponse(
            name = "Title",
            url = url,
            dataUrl = url,
            posterUrl = null,
            year = null,
            plot = null,
            rating = null,
            tags = emptyList(),
            recommendations = emptyList()
        )
    }
    
    override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
        // Implement streaming link extraction
        return emptyList()
    }
    
    override suspend fun getMainPage(): HomePageResponse {
        // Implement home page content
        return HomePageResponse(emptyList())
    }
}
```

---

## Provider Implementation

### Using TMDB for Metadata

Most extensions use TMDB for search and metadata:

```kotlin
private val tmdbApiKey = "YOUR_TMDB_API_KEY"
private val tmdbBaseUrl = "https://api.themoviedb.org/3"

override suspend fun search(query: String, page: Int, filters: Map<String, String>): SearchResponse {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val url = "$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encodedQuery&page=$page"
    
    val json = fetchJson(url)
    val results = mutableListOf<SearchResult>()
    
    val resultsArray = json.optJSONArray("results") ?: return SearchResponse(emptyList())
    
    for (i in 0 until resultsArray.length()) {
        val item = resultsArray.getJSONObject(i)
        val mediaType = item.optString("media_type")
        
        if (mediaType == "movie" || mediaType == "tv") {
            val id = item.optInt("id")
            val title = item.optString("title").ifEmpty { item.optString("name") }
            val posterPath = item.optString("poster_path")
            val posterUrl = if (posterPath.isNotEmpty()) 
                "https://image.tmdb.org/t/p/w500$posterPath" else null
            
            results.add(SearchResult(
                name = title,
                url = if (mediaType == "movie") id.toString() else "tv:$id",
                posterUrl = posterUrl,
                type = if (mediaType == "movie") ContentType.MOVIE else ContentType.TV_SERIES,
                year = item.optString("release_date").take(4).toIntOrNull()
            ))
        }
    }
    
    return SearchResponse(results, page < json.optInt("total_pages", 1))
}
```

### Implementing Streaming Sources

#### Approach 1: Direct API Scraping
```kotlin
override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
    val links = mutableListOf<ExtractorLink>()
    
    try {
        val apiUrl = "https://api.example.com/stream?id=$dataUrl"
        val response = fetchJson(apiUrl)
        val streamUrl = response.optString("url")
        
        if (streamUrl.isNotEmpty()) {
            links.add(ExtractorLink(
                source = "Example",
                name = "Example HD",
                url = streamUrl,
                referer = baseUrl,
                isM3u8 = streamUrl.contains(".m3u8")
            ))
        }
    } catch (e: Exception) {
        // Handle error
    }
    
    return links
}
```

#### Approach 2: Embed Players (Recommended)
```kotlin
override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
    val links = mutableListOf<ExtractorLink>()
    val tmdbId = dataUrl.split(":")[0]
    
    // VidSrc
    links.add(ExtractorLink(
        source = "VidSrc",
        name = "VidSrc",
        url = "https://vidsrc.to/embed/movie/$tmdbId",
        referer = "https://vidsrc.to/",
        isM3u8 = false
    ))
    
    // SuperEmbed
    links.add(ExtractorLink(
        source = "SuperEmbed",
        name = "SuperEmbed",
        url = "https://multiembed.mov/?video_id=$tmdbId&tmdb=1",
        referer = "https://multiembed.mov/",
        isM3u8 = false
    ))
    
    return links
}
```

### Handling TV Shows

```kotlin
override suspend fun load(url: String): LoadResponse {
    val isTv = url.startsWith("tv:")
    val id = if (isTv) url.substring(3) else url
    
    if (isTv) {
        val episodes = mutableListOf<Episode>()
        
        // Fetch season data
        val seasonUrl = "$tmdbBaseUrl/tv/$id/season/1?api_key=$tmdbApiKey"
        val sJson = fetchJson(seasonUrl)
        val eps = sJson.optJSONArray("episodes")
        
        if (eps != null) {
            for (j in 0 until eps.length()) {
                val ep = eps.getJSONObject(j)
                episodes.add(Episode(
                    name = ep.optString("name"),
                    season = 1,
                    episode = ep.optInt("episode_number"),
                    dataUrl = "tv:$id:1:${ep.optInt("episode_number")}",
                    posterUrl = null,
                    description = ep.optString("overview"),
                    date = ep.optString("air_date")
                ))
            }
        }
        
        return TvSeriesLoadResponse(
            name = "Show Title",
            url = url,
            posterUrl = null,
            year = null,
            plot = null,
            rating = null,
            tags = emptyList(),
            episodes = episodes,
            recommendations = emptyList()
        )
    } else {
        return MovieLoadResponse(/* ... */)
    }
}
```

---

## Repository Configuration

### repo.json Structure
```json
{
    "name": "Extension Repository Name",
    "description": "Repository description",
    "author": "Your Name",
    "url": "https://raw.githubusercontent.com/username/repo/main/repo.json",
    "version": 1,
    "extensions": [
        {
            "name": "Extension Name",
            "description": "Extension description",
            "version": 1,
            "versionName": "1.0",
            "lang": "en",
            "apkUrl": "https://raw.githubusercontent.com/username/repo/main/extension/extension.apk",
            "iconUrl": "https://example.com/icon.png",
            "sha256": null,
            "minAppVersion": 1,
            "sources": [
                "https://source1.com",
                "https://source2.com"
            ]
        }
    ]
}
```

### Version Management
- Increment `version` (integer) to trigger app updates
- Update `versionName` (string) for display purposes
- Users will automatically be notified of updates

---

## Building and Deployment

### Build APK
```bash
cd your-extension/
./gradlew clean assembleDebug
```

The APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Copy to Repository Root
```bash
cp app/build/outputs/apk/debug/app-debug.apk ./extension-name.apk
```

### Update Repository
1. Update `repo.json` with new version
2. Commit changes:
   ```bash
   git add .
   git commit -m "Update Extension Name to v2.0"
   git push
   ```

### Make Repository Public
Ensure your GitHub repository is **public** so the app can access:
- `repo.json`
- APK files
- Icons

---

## Troubleshooting

### Common Issues

#### 1. Extension Crashes on Load
**Problem**: Missing variable declarations or null pointer exceptions

**Solution**: Check for undefined variables, especially in loops:
```kotlin
// ❌ Wrong
for (j in 0 until eps.length()) {
    val epNum = ep.optInt("episode_number")  // 'ep' undefined!
}

// ✅ Correct
for (j in 0 until eps.length()) {
    val ep = eps.getJSONObject(j)
    val epNum = ep.optInt("episode_number")
}
```

#### 2. "Could not find streaming links"
**Possible Causes**:
- API endpoint is down
- Missing authentication/verification tokens
- Incorrect URL format
- Cloudflare protection

**Solutions**:
- Use multiple streaming sources as fallbacks
- Implement embed players (VidSrc, SuperEmbed, etc.)
- Add proper headers (Origin, Referer)
- Handle exceptions gracefully

#### 3. Extension Not Updating in App
**Solution**:
- Increment the `version` number in `repo.json`
- Ensure GitHub repository is public
- Clear app cache and reinstall extension

#### 4. Build Failures
**Common Fixes**:
```bash
# Clean build
./gradlew clean

# Update Gradle wrapper
./gradlew wrapper --gradle-version=8.0

# Check Java version
java -version  # Should be Java 11 or higher
```

### Debugging Tips

1. **Add Logging**:
```kotlin
try {
    // Your code
} catch (e: Exception) {
    e.printStackTrace()  // Logs to Android logcat
}
```

2. **Test with curl**:
```bash
curl -H "Origin: https://example.com" \
     -H "Referer: https://example.com/" \
     "https://api.example.com/endpoint"
```

3. **Validate JSON**:
```kotlin
val response = fetchJson(url)
println("Response: $response")  // Debug output
```

---

## Best Practices

### 1. Use Multiple Sources
Always implement multiple streaming sources for reliability:
```kotlin
// Try source 1
try { /* ... */ } catch (e: Exception) { }

// Try source 2
try { /* ... */ } catch (e: Exception) { }

// Try source 3
try { /* ... */ } catch (e: Exception) { }
```

### 2. Handle Errors Gracefully
```kotlin
override suspend fun loadLinks(dataUrl: String): List<ExtractorLink> {
    val links = mutableListOf<ExtractorLink>()
    
    // Never throw exceptions, just return empty list
    try {
        // Fetch links
    } catch (e: Exception) {
        return emptyList()
    }
    
    return links
}
```

### 3. Optimize Network Requests
```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

### 4. Cache Metadata
Consider caching TMDB responses to reduce API calls.

### 5. Use Proper Referers
Many streaming services check the Referer header:
```kotlin
val headers = mapOf(
    "Origin" to baseUrl,
    "Referer" to "$baseUrl/",
    "User-Agent" to "Mozilla/5.0..."
)
```

---

## Example: Complete Extension

See `xprime/` directory for a complete, working example that includes:
- ✅ TMDB integration for search and metadata
- ✅ TV show episode handling
- ✅ Multiple streaming sources
- ✅ Error handling
- ✅ Proper data models

---

## Resources

- **TMDB API**: https://developers.themoviedb.org/3
- **OkHttp Documentation**: https://square.github.io/okhttp/
- **Android Developer Guide**: https://developer.android.com/

---

## Support

For issues or questions:
1. Check this guide first
2. Review existing extensions for examples
3. Test API endpoints manually with curl/Postman
4. Check Android logcat for error messages

---

**Last Updated**: December 14, 2025  
**Version**: 2.0
