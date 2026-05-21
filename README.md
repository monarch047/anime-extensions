# StreamingUnity Extension for Aniyomi

[![Build](https://github.com/monarch047/anime-extensions/actions/workflows/build-streamingunity.yml/badge.svg)](https://github.com/monarch047/anime-extensions/actions/workflows/build-streamingunity.yml)

Custom [Aniyomi](https://github.com/aniyomiorg/aniyomi) extension for [StreamingUnity](https://streamingunity.dog) — a Laravel/Inertia.js SPA with VixCloud HLS streaming.

## Features

- Popular/Trending/Latest/Top 10 browsing
- Search by title
- Episode lists with season grouping
- HLS video extraction (480p/720p/1080p)
- Quality selector in preferences

## Installation

### Option 1: Add repo URL (auto-updates)

1. Open Aniyomi → Browse → Extensions
2. Tap the menu (⋮) → **Add extension repo**
3. Enter the following URL:

```
https://monarch047.github.io/anime-extensions/index.min.json
```

Or click the link below from your Android device:

[![Install](https://img.shields.io/badge/Install%20repo-red?style=flat-square)](https://intradeus.github.io/http-protocol-redirector/?r=aniyomi://add-repo?url=https://monarch047.github.io/anime-extensions/index.min.json)

> If the GitHub Pages URL doesn't work, use the raw URL instead:
> ```
> https://raw.githubusercontent.com/monarch047/anime-extensions/repo/index.min.json
> ```

### Option 2: Manual APK

Download the latest APK from the [Actions tab](https://github.com/monarch047/anime-extensions/actions) and install it manually.

## Development

```bash
# Build the extension
./gradlew :src:en:streamingunity:assembleRelease

# Build with formatting check
./gradlew :src:en:streamingunity:spotlessApply
./gradlew :src:en:streamingunity:assembleRelease -x spotlessKotlinCheck
```

## Source Structure

```
src/en/streamingunity/
├── build.gradle
├── res/                    # App icons
└── src/
    └── eu/kanade/tachiyomi/animeextension/en/streamingunity/
        ├── StreamingUnity.kt           # Main source
        ├── StreamingUnityDto.kt        # Inertia.js data classes
        ├── StreamingUnityExtractor.kt  # VixCloud HLS extraction
        └── StreamingUnityUrlActivity.kt
```

## Site Info

| Route | Page |
|-------|------|
| `/en` | Home (trending, latest, top10) |
| `/en/titles/{id}-{slug}` | Title details |
| `/en/browse/{name}` | Browse/category |
| `/en/search?q=...` | Search |
| `/en/watch/{id}?episode_id={eid}` | Watch / iframe |

Data source: `<div id="app" data-page='{...JSON...}'>` (Inertia.js props — no API calls or browser rendering needed).

## Video Extraction

1. Fetch `/en/iframe/{title_id}?episode_id={ep_id}` → VixCloud embed URL
2. Parse VixCloud embed → extract `token`, `expires`, `playlist` URL
3. Build HLS master playlist URL with token
4. Parse `#EXT-X-STREAM-INF` for quality variants
