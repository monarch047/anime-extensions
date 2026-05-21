package eu.kanade.tachiyomi.animeextension.en.streamingunity

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyAsText
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.regex.Pattern

/**
 * Extracts video URLs from VixCloud (VixSrc) embed pages.
 *
 * Flow:
 * 1. Fetch the iframe page at /en/iframe/{title_id}
 * 2. Extract the VixCloud embed URL
 * 3. Fetch the VixCloud embed page to get the real token and playlist URL
 * 4. Fetch the playlist to get the .m3u8 master playlist
 * 5. Parse .m3u8 to extract quality variants
 */
class StreamingUnityExtractor(private val client: OkHttpClient, private val headers: Headers) {

    /**
     * Get video URLs for an episode.
     */
    suspend fun getVideos(
        iframeUrl: String,
        episodeId: Long,
        episodeNum: Int,
        titleName: String,
        seasonNum: Int,
    ): List<Video> {
        // Step 1: Fetch the iframe page to get the VixCloud embed URL
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).awaitSuccess()
        val iframeHtml = iframeResponse.bodyAsText()

        val vixcloudUrl = extractVixcloudUrl(iframeHtml) ?: return emptyList()
        if (vixcloudUrl.contains("***")) return emptyList() // Token is masked

        // Step 2: Fetch the VixCloud embed page
        val vixcloudResponse = client.newCall(
            GET(vixcloudUrl, headers.newBuilder().set("Referer", iframeUrl).build())
        ).awaitSuccess()
        val vixcloudHtml = vixcloudResponse.bodyAsText()

        // Step 3: Extract master playlist URL and token
        val playlistInfo = extractPlaylistInfo(vixcloudHtml) ?: return emptyList()
        val (playlistUrl, token) = playlistInfo

        // Step 4: Fetch the playlist
        val fullPlaylistUrl = "$playlistUrl&token=$token"
        val playlistResponse = client.newCall(
            GET(fullPlaylistUrl, headers.newBuilder()
                .set("Referer", vixcloudUrl)
                .set("Origin", "https://vixcloud.co")
                .build())
        ).awaitSuccess()

        val playlistBody = playlistResponse.bodyAsText()

        // Step 5: Parse .m3u8 to extract quality variants
        return parseM3u8Playlist(playlistBody, fullPlaylistUrl)
            .ifEmpty {
                // If no .m3u8 found, the playlist body itself might be the video URL
                listOf(
                    Video(
                        videoUrl = fullPlaylistUrl,
                        quality = "Default",
                        videoUrl = fullPlaylistUrl,
                        headers = headers,
                    )
                )
            }
    }

    /**
     * Extract the VixCloud embed URL from the iframe page HTML.
     */
    private fun extractVixcloudUrl(html: String): String? {
        val pattern = Pattern.compile(
            """<iframe[^>]+src="([^"]*vixcloud[^"]*)" """.trimIndent(),
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).replace("&amp;", "&")
        } else null
    }

    /**
     * Extract the master playlist URL and token from the VixCloud page JavaScript.
     */
    private fun extractPlaylistInfo(html: String): Pair<String, String>? {
        // Extract token
        val tokenPattern = Pattern.compile("'token':\\s*'([^']+)'")
        val tokenMatcher = tokenPattern.matcher(html)

        // Extract playlist base URL
        val urlPattern = Pattern.compile("url:\\s*'([^']+playlist/\\d+[^']*)'")
        val urlMatcher = urlPattern.matcher(html)

        return if (tokenMatcher.find() && urlMatcher.find()) {
            val token = tokenMatcher.group(1)
            var playlistUrl = urlMatcher.group(1)
            if (playlistUrl.contains("***")) return null // Token placeholder
            playlistUrl to token
        } else null
    }

    /**
     * Parse an .m3u8 playlist into quality-sorted Video objects.
     */
    private fun parseM3u8Playlist(playlistBody: String, baseUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        if (!playlistBody.contains("#EXTM3U")) {
            return emptyList()
        }

        // Check if this is a variant playlist (master) or direct stream
        if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            // Master playlist — extract variant URLs
            val lines = playlistBody.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Extract resolution / bandwidth
                    val resolution = extractQuality(line)
                    i++
                    if (i < lines.size) {
                        val url = resolveUrl(lines[i].trim(), baseUrl)
                        videos.add(
                            Video(
                                videoUrl = url,
                                quality = resolution,
                                videoUrl = url,
                                headers = headers,
                            )
                        )
                    }
                }
                i++
            }
        } else {
            // Direct stream URL
            val urlPattern = Pattern.compile("https?://[^\\s\"']+\\.(m3u8|mp4)")
            val matcher = urlPattern.matcher(playlistBody)
            if (matcher.find()) {
                videos.add(
                    Video(
                        videoUrl = matcher.group(),
                        quality = "Default",
                        videoUrl = matcher.group(),
                        headers = headers,
                    )
                )
            }
        }

        // Sort by quality descending (highest first)
        return videos.sortedByDescending { extractQualityInt(it.quality) }
    }

    /**
     * Extract quality label from an EXT-X-STREAM-INF line.
     */
    private fun extractQuality(infLine: String): String {
        val resPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")
        val resMatcher = resPattern.matcher(infLine)
        if (resMatcher.find()) {
            return "${resMatcher.group(2)}p"
        }

        val bwPattern = Pattern.compile("BANDWIDTH=(\\d+)")
        val bwMatcher = bwPattern.matcher(infLine)
        if (bwMatcher.find()) {
            val bw = bwMatcher.group(1).toIntOrNull() ?: 0
            return when {
                bw > 5_000_000 -> "1080p"
                bw > 2_500_000 -> "720p"
                bw > 1_000_000 -> "480p"
                else -> "360p"
            }
        }

        return "Default"
    }

    /**
     * Parse quality string to int for sorting.
     */
    private fun extractQualityInt(quality: String): Int {
        return when {
            quality.contains("1080") || quality.contains("4K") -> 1080
            quality.contains("720") -> 720
            quality.contains("480") -> 480
            quality.contains("360") -> 360
            else -> 0
        }
    }

    /**
     * Resolve a relative URL against the base playlist URL.
     */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val base = baseUrl.substringBeforeLast("/")
        return "$base/$url"
    }
}
