package eu.kanade.tachiyomi.animeextension.en.streamingunity

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class StreamingUnityExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    suspend fun getVideos(iframeUrl: String): List<Video> {
        // Step 1: Fetch the StreamingUnity iframe page
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).awaitSuccess()
        val iframeHtml = iframeResponse.bodyString()

        val vixcloudUrl = extractVixcloudUrl(iframeHtml) ?: return emptyList()

        // Step 2: Fetch the vixcloud embed page.
        // Origin must be the StreamingUnity domain (the true embedding origin),
        // not vixcloud.co itself (which would be a self-referencing origin).
        val vixHeaders = headers.newBuilder()
            .set("Referer", iframeUrl)
            .set("Origin", "https://streamingunity.dog")
            .build()
        val vixcloudResponse = client.newCall(GET(vixcloudUrl, vixHeaders)).awaitSuccess()
        val vixcloudHtml = vixcloudResponse.bodyString()

        val token = extractJsVar(vixcloudHtml, "token") ?: return emptyList()
        val expires = extractJsVar(vixcloudHtml, "expires") ?: return emptyList()
        val playlistBase = extractPlaylistUrl(vixcloudHtml) ?: return emptyList()

        val masterUrl = "$playlistBase&token=$token&expires=$expires&h=1&lang=en"

        // Step 3: Fetch the HLS master playlist to discover available qualities.
        // Referer = the vixcloud embed page; Origin = StreamingUnity (true cross-origin source).
        val playlistHeaders = vixHeaders.newBuilder()
            .set("Referer", vixcloudUrl)
            .set("Origin", "https://streamingunity.dog")
            .build()
        val playlistResponse = client.newCall(GET(masterUrl, playlistHeaders)).awaitSuccess()
        val playlistBody = playlistResponse.bodyString()

        if (!playlistBody.contains("#EXTM3U")) return emptyList()

        // Step 4: Extract quality labels from the master playlist.
        // IMPORTANT: We pass the master playlist URL to the video player, NOT individual
        // variant stream URLs. HLS variant URLs carry video-only segments; audio tracks
        // are linked via #EXT-X-MEDIA in the master playlist and ExoPlayer needs to
        // read the full master to reconstruct the audio+video association.
        val qualities = mutableListOf<String>()
        val streamInfPattern = Pattern.compile(
            "#EXT-X-STREAM-INF:.*?RESOLUTION=(\\d+)x(\\d+)",
            Pattern.DOTALL,
        )
        val streamInfMatcher = streamInfPattern.matcher(playlistBody)
        while (streamInfMatcher.find()) {
            qualities.add("${streamInfMatcher.group(2)}p")
        }

        if (qualities.isNotEmpty()) {
            return qualities
                .sortedByDescending { parseHeight(it) }
                .map { quality ->
                    Video(
                        url = masterUrl,
                        quality = quality,
                        videoUrl = masterUrl,
                        headers = playlistHeaders,
                    )
                }
        }

        // Fallback: return the master URL as a single HLS entry
        return listOf(
            Video(
                url = masterUrl,
                quality = "HLS",
                videoUrl = masterUrl,
                headers = playlistHeaders,
            ),
        )
    }

    private fun extractVixcloudUrl(html: String): String? {
        val pattern = Pattern.compile(
            """<iframe[^>]+src="([^"]*vixcloud[^"]*)""",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1).replace("&amp;", "&")
        } else {
            null
        }
    }

    private fun extractJsVar(html: String, varName: String): String? {
        val pattern = Pattern.compile(
            "'$varName':\\s*'([^']+)'",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            val value = matcher.group(1)
            if (value.contains("***")) null else value
        } else {
            null
        }
    }

    private fun extractPlaylistUrl(html: String): String? {
        val pattern = Pattern.compile(
            """url:\s*'([^']+playlist/\d+[^']*)'""",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            val url = matcher.group(1)
            if (url.contains("***")) null else url
        } else {
            null
        }
    }

    private fun parseHeight(quality: String): Int {
        val match = Pattern.compile("(\\d+)").matcher(quality)
        return if (match.find()) match.group(1).toIntOrNull() ?: 0 else 0
    }
}
