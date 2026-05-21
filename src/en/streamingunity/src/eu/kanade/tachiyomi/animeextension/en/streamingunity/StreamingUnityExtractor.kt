package eu.kanade.tachiyomi.animeextension.en.streamingunity

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
 * 1. Fetch the iframe page at /en/iframe/{title_id}?episode_id={eid}
 * 2. Extract the VixCloud embed URL from the iframe
 * 3. Fetch VixCloud embed page to extract:
 *    - Real token from window.masterPlaylist.params.token
 *    - Expires from window.masterPlaylist.params.expires
 *    - Playlist base URL from window.masterPlaylist.url
 * 4. Build master playlist URL: {base}&token={token}&expires={expires}&h=1&lang=en
 * 5. Fetch master playlist → returns HLS with EXT-X-STREAM-INF entries
 * 6. Parse EXT-X-STREAM-INF for quality variants, fetch each variant URL
 */
class StreamingUnityExtractor(private val client: OkHttpClient, private val headers: Headers) {

    suspend fun getVideos(iframeUrl: String): List<Video> {
        // Step 1: Fetch the iframe page to get the VixCloud embed URL
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).awaitSuccess()
        val iframeHtml = iframeResponse.bodyAsText()

        val vixcloudUrl = extractVixcloudUrl(iframeHtml) ?: return emptyList()

        // Step 2: Fetch the VixCloud embed page with proper Referer
        val vixHeaders = headers.newBuilder()
            .set("Referer", iframeUrl)
            .build()
        val vixcloudResponse = client.newCall(GET(vixcloudUrl, vixHeaders)).awaitSuccess()
        val vixcloudHtml = vixcloudResponse.bodyAsText()

        // Step 3: Extract token, expires, and playlist base URL from JavaScript
        val token = extractJsVar(vixcloudHtml, "token") ?: return emptyList()
        val expires = extractJsVar(vixcloudHtml, "expires") ?: return emptyList()
        val playlistBase = extractPlaylistUrl(vixcloudHtml) ?: return emptyList()

        // Step 4: Build master playlist URL
        // Format: {base}&token={token}&expires={expires}&h=1&lang=en
        val masterUrl = "$playlistBase&token=$token&expires=$expires&h=1&lang=en"

        // Step 5: Fetch the master playlist
        val playlistHeaders = vixHeaders.newBuilder()
            .set("Origin", "https://vixcloud.co")
            .build()
        val playlistResponse = client.newCall(GET(masterUrl, playlistHeaders)).awaitSuccess()
        val playlistBody = playlistResponse.bodyAsText()

        // Step 6: Parse master playlist
        return parseMasterPlaylist(playlistBody, token, expires)
    }

    /**
     * Extract the VixCloud embed URL from the iframe page.
     */
    private fun extractVixcloudUrl(html: String): String? {
        val pattern = Pattern.compile(
            """<iframe[^>]+src="([^"]*vixcloud[^"]*)"""",
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).replace("&amp;", "&")
        } else null
    }

    /**
     * Extract a JS variable value from window.masterPlaylist.params.
     */
    private fun extractJsVar(html: String, varName: String): String? {
        val pattern = Pattern.compile(
            "'$varName':\\s*'([^']+)'",
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            val value = matcher.group(1)
            if (value.contains("***")) null else value
        } else null
    }

    /**
     * Extract the playlist base URL from window.masterPlaylist.url.
     */
    private fun extractPlaylistUrl(html: String): String? {
        val pattern = Pattern.compile(
            """url:\s*'([^']+playlist/\d+[^']*)'""",
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            val url = matcher.group(1)
            if (url.contains("***")) null else url
        } else null
    }

    /**
     * Parse an HLS master playlist into quality-sorted Video objects.
     *
     * Master playlist contains:
     * - #EXT-X-MEDIA for audio/subtitle tracks (ignored)
     * - #EXT-X-STREAM-INF for video renditions (what we want)
     */
    private fun parseMasterPlaylist(
        playlistBody: String,
        token: String,
        expires: String,
    ): List<Video> {
        val videos = mutableListOf<Video>()

        if (!playlistBody.contains("#EXTM3U")) return emptyList()

        // The video rendition URLs in the playlist have *** for token.
        // We need to replace *** with the real token.
        // Pattern: https://vixcloud.co/playlist/XXX?type=video&rendition=720p&token=***&expires=...&b=1
        val streamInfPattern = Pattern.compile(
            "#EXT-X-STREAM-INF:.*?RESOLUTION=(\\d+)x(\\d+).*?\\n(https?://[^\\n]+)",
            Pattern.DOTALL
        )
        val streamInfMatcher = streamInfPattern.matcher(playlistBody)

        while (streamInfMatcher.find()) {
            val height = streamInfMatcher.group(2) // e.g., "720"
            val rawUrl = streamInfMatcher.group(3).trim()
            val quality = "${height}p"

            // Replace *** with real token in the URL
            val realUrl = rawUrl.replace("***", token) + "&expires=$expires"

            videos.add(
                Video(
                    videoUrl = realUrl,
                    quality = quality,
                    videoUrl = realUrl,
                    headers = headers,
                )
            )
        }

        // If no STREAM-INF found but has EXT-X-MEDIA, try to guess renditions
        if (videos.isEmpty() && playlistBody.contains("EXT-X-MEDIA")) {
            // Try common renditions
            for (quality in listOf("1080p" to 1080, "720p" to 720, "480p" to 480)) {
                val (label, height) = quality
                val rendition = label.replace("p", "")
                // Build URL from the base pattern
                val baseUrlMatch = Pattern.compile(
                    """type=audio&rendition=\w+&token=\*\*\*&expires=(\d+)""",
                    Pattern.DOTALL
                ).matcher(playlistBody)

                if (baseUrlMatch.find()) {
                    // Extract base URL structure and modify for video
                    val audioUrl = "https://vixcloud.co/playlist/${extractVideoId(playlistBody)}" +
                        "?type=video&rendition=${rendition}p&token=$token&expires=$expires&b=1"
                    videos.add(
                        Video(
                            videoUrl = audioUrl,
                            quality = label,
                            videoUrl = audioUrl,
                            headers = headers,
                        )
                    )
                }
            }
        }

        // Fallback: use the master playlist URL directly
        if (videos.isEmpty()) {
            videos.add(
                Video(
                    videoUrl = playlistBody,
                    quality = "HLS",
                    videoUrl = playlistBody,
                    headers = headers,
                )
            )
        }

        return videos.sortedByDescending { parseHeight(it.quality) }
    }

    /**
     * Extract video/scws_id from the playlist URL.
     */
    private fun extractVideoId(playlistBody: String): String {
        val match = Pattern.compile("playlist/(\\d+)").matcher(playlistBody)
        return if (match.find()) match.group(1) else "0"
    }

    /**
     * Parse quality label to numeric height for sorting.
     */
    private fun parseHeight(quality: String): Int {
        val match = Pattern.compile("(\\d+)").matcher(quality)
        return if (match.find()) match.group(1).toIntOrNull() ?: 0 else 0
    }
}
