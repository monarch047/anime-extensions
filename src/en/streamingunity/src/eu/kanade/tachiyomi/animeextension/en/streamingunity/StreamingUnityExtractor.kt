package eu.kanade.tachiyomi.animeextension.en.streamingunity

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyAsText
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class StreamingUnityExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    suspend fun getVideos(iframeUrl: String): List<Video> {
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).awaitSuccess()
        val iframeHtml = iframeResponse.bodyAsText()

        val vixcloudUrl = extractVixcloudUrl(iframeHtml) ?: return emptyList()

        val vixHeaders = headers.newBuilder()
            .set("Referer", iframeUrl)
            .build()
        val vixcloudResponse = client.newCall(GET(vixcloudUrl, vixHeaders)).awaitSuccess()
        val vixcloudHtml = vixcloudResponse.bodyAsText()

        val token = extractJsVar(vixcloudHtml, "token") ?: return emptyList()
        val expires = extractJsVar(vixcloudHtml, "expires") ?: return emptyList()
        val playlistBase = extractPlaylistUrl(vixcloudHtml) ?: return emptyList()

        val masterUrl = "$playlistBase&token=$token&expires=$expires&h=1&lang=en"

        val playlistHeaders = vixHeaders.newBuilder()
            .set("Origin", "https://vixcloud.co")
            .build()
        val playlistResponse = client.newCall(GET(masterUrl, playlistHeaders)).awaitSuccess()
        val playlistBody = playlistResponse.bodyAsText()

        return parseMasterPlaylist(playlistBody, token, expires)
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

    private fun parseMasterPlaylist(
        playlistBody: String,
        token: String,
        expires: String,
    ): List<Video> {
        val videos = mutableListOf<Video>()

        if (!playlistBody.contains("#EXTM3U")) return emptyList()

        val streamInfPattern = Pattern.compile(
            "#EXT-X-STREAM-INF:.*?RESOLUTION=(\\d+)x(\\d+).*?\\n(https?://[^\\n]+)",
            Pattern.DOTALL,
        )
        val streamInfMatcher = streamInfPattern.matcher(playlistBody)

        while (streamInfMatcher.find()) {
            val height = streamInfMatcher.group(2)
            val rawUrl = streamInfMatcher.group(3).trim()
            val quality = "${height}p"

            val realUrl = rawUrl.replace("***", token) + "&expires=$expires"

            videos.add(
                Video(
                    videoUrl = realUrl,
                    quality = quality,
                    videoUrl = realUrl,
                    headers = headers,
                ),
            )
        }

        if (videos.isEmpty() && playlistBody.contains("EXT-X-MEDIA")) {
            for (quality in listOf("1080p" to 1080, "720p" to 720, "480p" to 480)) {
                val (label) = quality
                val rendition = label.replace("p", "")
                val baseUrlMatch = Pattern.compile(
                    """type=audio&rendition=\w+&token=\*\*\*&expires=(\d+)""",
                    Pattern.DOTALL,
                ).matcher(playlistBody)

                if (baseUrlMatch.find()) {
                    val audioUrl = "https://vixcloud.co/playlist/${extractVideoId(playlistBody)}" +
                        "?type=video&rendition=${rendition}p&token=$token&expires=$expires&b=1"
                    videos.add(
                        Video(
                            videoUrl = audioUrl,
                            quality = label,
                            videoUrl = audioUrl,
                            headers = headers,
                        ),
                    )
                }
            }
        }

        if (videos.isEmpty()) {
            videos.add(
                Video(
                    videoUrl = playlistBody,
                    quality = "HLS",
                    videoUrl = playlistBody,
                    headers = headers,
                ),
            )
        }

        return videos.sortedByDescending { parseHeight(it.quality) }
    }

    private fun extractVideoId(playlistBody: String): String {
        val match = Pattern.compile("playlist/(\\d+)").matcher(playlistBody)
        return if (match.find()) match.group(1) else "0"
    }

    private fun parseHeight(quality: String): Int {
        val match = Pattern.compile("(\\d+)").matcher(quality)
        return if (match.find()) match.group(1).toIntOrNull() ?: 0 else 0
    }
}
