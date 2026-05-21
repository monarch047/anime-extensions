     1|package eu.kanade.tachiyomi.animeextension.en.streamingunity
     2|
     3|import eu.kanade.tachiyomi.animesource.model.Video
     4|import eu.kanade.tachiyomi.network.GET
     5|import eu.kanade.tachiyomi.network.awaitSuccess
     6|import keiyoushi.utils.bodyAsText
     7|import okhttp3.Headers
     8|import okhttp3.OkHttpClient
     9|import java.util.regex.Pattern
    10|
    11|/**
    12| * Extracts video URLs from VixCloud (VixSrc) embed pages.
    13| *
    14| * Flow:
    15| * 1. Fetch the iframe page at /en/iframe/{title_id}?episode_id={eid}
    16| * 2. Extract the VixCloud embed URL from the iframe
    17| * 3. Fetch VixCloud embed page to extract:
    18| *    - Real token from window.masterPlaylist.params.token
    19| *    - Expires from window.masterPlaylist.params.expires
    20| *    - Playlist base URL from window.masterPlaylist.url
    21| * 4. Build master playlist URL: {base}&token={token}&expires={expires}&h=1&lang=en
    22| * 5. Fetch master playlist → returns HLS with EXT-X-STREAM-INF entries
    23| * 6. Parse EXT-X-STREAM-INF for quality variants, fetch each variant URL
    24| */
    25|class StreamingUnityExtractor(private val client: OkHttpClient, private val headers: Headers) {
    26|
    27|    suspend fun getVideos(iframeUrl: String): List<Video> {
    28|        // Step 1: Fetch the iframe page to get the VixCloud embed URL
    29|        val iframeResponse = client.newCall(GET(iframeUrl, headers)).awaitSuccess()
    30|        val iframeHtml = iframeResponse.bodyAsText()
    31|
    32|        val vixcloudUrl = extractVixcloudUrl(iframeHtml) ?: return emptyList()
    33|
    34|        // Step 2: Fetch the VixCloud embed page with proper Referer
    35|        val vixHeaders = headers.newBuilder()
    36|            .set("Referer", iframeUrl)
    37|            .build()
    38|        val vixcloudResponse = client.newCall(GET(vixcloudUrl, vixHeaders)).awaitSuccess()
    39|        val vixcloudHtml = vixcloudResponse.bodyAsText()
    40|
    41|        // Step 3: Extract token, expires, and playlist base URL from JavaScript
    42|        val token = extractJsVar(vixcloudHtml, "token") ?: return emptyList()
    43|        val expires = extractJsVar(vixcloudHtml, "expires") ?: return emptyList()
    44|        val playlistBase = extractPlaylistUrl(vixcloudHtml) ?: return emptyList()
    45|
    46|        // Step 4: Build master playlist URL
    47|        // Format: {base}&token={token}&expires={expires}&h=1&lang=en
    48|        val masterUrl = "$playlistBase&token=$token&expires=$expires&h=1&lang=en"
    49|
    50|        // Step 5: Fetch the master playlist
    51|        val playlistHeaders = vixHeaders.newBuilder()
    52|            .set("Origin", "https://vixcloud.co")
    53|            .build()
    54|        val playlistResponse = client.newCall(GET(masterUrl, playlistHeaders)).awaitSuccess()
    55|        val playlistBody = playlistResponse.bodyAsText()
    56|
    57|        // Step 6: Parse master playlist
    58|        return parseMasterPlaylist(playlistBody, token, expires)
    59|    }
    60|
    61|    /**
    62|     * Extract the VixCloud embed URL from the iframe page.
    63|     */
    64|    private fun extractVixcloudUrl(html: String): String? {
    65|        val pattern = Pattern.compile(
    66|            """<iframe[^>]+src="([^"]*vixcloud[^"]*)"""",
    67|            Pattern.DOTALL
    68|        )
    69|        val matcher = pattern.matcher(html)
    70|        return if (matcher.find()) {
    71|            matcher.group(1).replace("&amp;", "&")
    72|        } else {
            null
        }
    73|    }
    74|
    75|    /**
    76|     * Extract a JS variable value from window.masterPlaylist.params.
    77|     */
    78|    private fun extractJsVar(html: String, varName: String): String? {
    79|        val pattern = Pattern.compile(
    80|            "'$varName':\\s*'([^']+)'",
    81|            Pattern.DOTALL
    82|        )
    83|        val matcher = pattern.matcher(html)
    84|        return if (matcher.find()) {
    85|            val value = matcher.group(1)
    86|            if (value.contains("***")) null else value
    87|        } else {
            null
        }
    88|    }
    89|
    90|    /**
    91|     * Extract the playlist base URL from window.masterPlaylist.url.
    92|     */
    93|    private fun extractPlaylistUrl(html: String): String? {
    94|        val pattern = Pattern.compile(
    95|            """url:\s*'([^']+playlist/\d+[^']*)'""",
    96|            Pattern.DOTALL
    97|        )
    98|        val matcher = pattern.matcher(html)
    99|        return if (matcher.find()) {
   100|            val url = matcher.group(1)
   101|            if (url.contains("***")) null else url
   102|        } else {
            null
        }
   103|    }
   104|
   105|    /**
   106|     * Parse an HLS master playlist into quality-sorted Video objects.
   107|     *
   108|     * Master playlist contains:
   109|     * - #EXT-X-MEDIA for audio/subtitle tracks (ignored)
   110|     * - #EXT-X-STREAM-INF for video renditions (what we want)
   111|     */
   112|    private fun parseMasterPlaylist(
   113|        playlistBody: String,
   114|        token: String,
   115|        expires: String,
   116|    ): List<Video> {
   117|        val videos = mutableListOf<Video>()
   118|
   119|        if (!playlistBody.contains("#EXTM3U")) return emptyList()
   120|
   121|        // The video rendition URLs in the playlist have *** for token.
   122|        // We need to replace *** with the real token.
   123|        // Pattern: https://vixcloud.co/playlist/XXX?type=video&rendition=720p&token=***&expires=...&b=1
   124|        val streamInfPattern = Pattern.compile(
   125|            "#EXT-X-STREAM-INF:.*?RESOLUTION=(\\d+)x(\\d+).*?\\n(https?://[^\\n]+)",
   126|            Pattern.DOTALL
   127|        )
   128|        val streamInfMatcher = streamInfPattern.matcher(playlistBody)
   129|
   130|        while (streamInfMatcher.find()) {
   131|            val height = streamInfMatcher.group(2) // e.g., "720"
   132|            val rawUrl = streamInfMatcher.group(3).trim()
   133|            val quality = "${height}p"
   134|
   135|            // Replace *** with real token in the URL
   136|            val realUrl = rawUrl.replace("***", token) + "&expires=$expires"
   137|
   138|            videos.add(
   139|                Video(
   140|                    videoUrl = realUrl,
   141|                    quality = quality,
   142|                    videoUrl = realUrl,
   143|                    headers = headers,
   144|                )
   145|            )
   146|        }
   147|
   148|        // If no STREAM-INF found but has EXT-X-MEDIA, try to guess renditions
   149|        if (videos.isEmpty() && playlistBody.contains("EXT-X-MEDIA")) {
   150|            // Try common renditions
   151|            for (quality in listOf("1080p" to 1080, "720p" to 720, "480p" to 480)) {
   152|                val (label, height) = quality
   153|                val rendition = label.replace("p", "")
   154|                // Build URL from the base pattern
   155|                val baseUrlMatch = Pattern.compile(
   156|                    """type=audio&rendition=\w+&token=\*\*\*&expires=(\d+)""",
   157|                    Pattern.DOTALL
   158|                ).matcher(playlistBody)
   159|
   160|                if (baseUrlMatch.find()) {
   161|                    // Extract base URL structure and modify for video
   162|                    val audioUrl = "https://vixcloud.co/playlist/${extractVideoId(playlistBody)}" +
   163|                        "?type=video&rendition=${rendition}p&token=$token&expires=$expires&b=1"
   164|                    videos.add(
   165|                        Video(
   166|                            videoUrl = audioUrl,
   167|                            quality = label,
   168|                            videoUrl = audioUrl,
   169|                            headers = headers,
   170|                        )
   171|                    )
   172|                }
   173|            }
   174|        }
   175|
   176|        // Fallback: use the master playlist URL directly
   177|        if (videos.isEmpty()) {
   178|            videos.add(
   179|                Video(
   180|                    videoUrl = playlistBody,
   181|                    quality = "HLS",
   182|                    videoUrl = playlistBody,
   183|                    headers = headers,
   184|                )
   185|            )
   186|        }
   187|
   188|        return videos.sortedByDescending { parseHeight(it.quality) }
   189|    }
   190|
   191|    /**
   192|     * Extract video/scws_id from the playlist URL.
   193|     */
   194|    private fun extractVideoId(playlistBody: String): String {
   195|        val match = Pattern.compile("playlist/(\\d+)").matcher(playlistBody)
   196|        return if (match.find()) match.group(1) else "0"
   197|    }
   198|
   199|    /**
   200|     * Parse quality label to numeric height for sorting.
   201|     */
   202|    private fun parseHeight(quality: String): Int {
   203|        val match = Pattern.compile("(\\d+)").matcher(quality)
   204|        return if (match.find()) match.group(1).toIntOrNull() ?: 0 else 0
   205|    }
   206|}
   207|