package eu.kanade.tachiyomi.animeextension.en.streamingunity

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.parser.Parser
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

class StreamingUnity :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "StreamingUnity"
    override val baseUrl = "https://streamingunity.dog"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences by getPreferencesLazy()

    private val injectApplication by injectLazy<Application>()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", "$baseUrl/en/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val sliderName = when (preferences.getString("popular", "trending")) {
            "latest" -> "latest"
            "top10" -> "top10"
            else -> "trending"
        }
        return GET("$baseUrl/en/browse/$sliderName", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val pageData = extractPageData(response.bodyString()) ?: return AnimesPage(emptyList(), false)
        val titles = pageData.props.titles ?: pageData.props.sliders?.firstOrNull()?.titles ?: emptyList()
        return AnimesPage(titles.map { it.toSAnime() }, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/en/browse/latest", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/en/search?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val pageData = extractPageData(response.bodyString()) ?: return AnimesPage(emptyList(), false)
        val titles = pageData.props.titles ?: emptyList()
        return AnimesPage(titles.map { it.toSAnime() }, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = try {
            client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        } catch (e: Exception) {
            throw e
        }

        return if (response.code == 404) {
            response.close()
            // Recovery: search by name
            val searchRequest = searchAnimeRequest(1, anime.title, AnimeFilterList())
            val searchResponse = client.newCall(searchRequest).awaitSuccess()
            val searchPage = searchAnimeParse(searchResponse)
            val matchedAnime = searchPage.animes.firstOrNull {
                it.title.equals(anime.title, ignoreCase = true) ||
                    cleanTitle(it.title) == cleanTitle(anime.title)
            } ?: throw Exception("Title '${anime.title}' not found on site (404)")

            val recoveryResponse = client.newCall(GET("$baseUrl${matchedAnime.url}", headers)).awaitSuccess()
            val details = animeDetailsParse(recoveryResponse)
            details.url = matchedAnime.url // Update the URL
            details
        } else {
            animeDetailsParse(response)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val pageData = extractPageData(response.bodyString()) ?: return SAnime.create()
        val titleDetail = pageData.props.title ?: return SAnime.create()

        val cdnUrl = pageData.props.cdn_url

        return SAnime.create().apply {
            title = titleDetail.name
            description = buildString {
                append(titleDetail.plot ?: "")
                append("\n\n")
                append("Score: ${titleDetail.score ?: "?"}\u2605")
                append("\nStatus: ${titleDetail.status ?: "Unknown"}")
                append("\nType: ${titleDetail.type?.uppercase() ?: "UNKNOWN"}")
                append("\nViews: ${titleDetail.views ?: "?"}")
                titleDetail.release_date?.let { append("\nRelease: $it") }
                titleDetail.last_air_date?.let { append("\nLast aired: $it") }
                titleDetail.age?.let { append("\nAge rating: $it+") }
            }
            status = parseStatus(titleDetail.status)
            genre = titleDetail.genres?.joinToString(", ") { it.name }

            val poster = titleDetail.images?.find { it.type == "poster" }
            thumbnail_url = poster?.let { "$cdnUrl/images/${it.filename}" }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var url = anime.url
        var response = client.newCall(GET("$baseUrl$url", headers)).awaitSuccess()

        if (response.code == 404) {
            response.close()
            // Recovery: search by name
            val searchRequest = searchAnimeRequest(1, anime.title, AnimeFilterList())
            val searchResponse = client.newCall(searchRequest).awaitSuccess()
            val searchPage = searchAnimeParse(searchResponse)
            val matchedAnime = searchPage.animes.firstOrNull {
                it.title.equals(anime.title, ignoreCase = true) ||
                    cleanTitle(it.title) == cleanTitle(anime.title)
            } ?: throw Exception("Title '${anime.title}' not found on site (404)")

            url = matchedAnime.url
            response = client.newCall(GET("$baseUrl$url", headers)).awaitSuccess()
        }

        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP error ${response.code}")
        }

        val html = response.bodyString()
        val pageData = extractPageData(html) ?: throw Exception("Failed to parse page data")
        val titleDetail = pageData.props.title ?: throw Exception("Title details not found in page data")

        if (titleDetail.type == "movie" || pageData.props.loadedSeason == null) {
            return listOf(
                SEpisode.create().apply {
                    name = titleDetail.name
                    episode_number = 1f
                    setUrlWithoutDomain("/en/watch/${titleDetail.id}")
                }
            )
        }

        val loadedSeason = pageData.props.loadedSeason ?: throw Exception("No loaded season found")

        val episodesList = parseEpisodes(pageData).toMutableList()

        // Fetch other seasons in parallel using coroutine helper
        val otherSeasons = titleDetail.seasons.filter { it.number != loadedSeason.number }
        if (otherSeasons.isNotEmpty()) {
            val otherEpisodes = otherSeasons.parallelMap { season ->
                val seasonUrl = "$baseUrl$url?season=${season.number}"
                try {
                    val seasonResponse = client.newCall(GET(seasonUrl, headers)).awaitSuccess()
                    if (seasonResponse.isSuccessful) {
                        val seasonPageData = extractPageData(seasonResponse.bodyString())
                        if (seasonPageData != null) {
                            parseEpisodes(seasonPageData)
                        } else {
                            emptyList()
                        }
                    } else {
                        seasonResponse.close()
                        emptyList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }.flatten()

            episodesList.addAll(otherEpisodes)
        }

        episodesList.sortWith(
            compareByDescending<SEpisode> { ep ->
                ep.scanlator?.removePrefix("S")?.toIntOrNull() ?: 0
            }.thenByDescending { it.episode_number },
        )

        return episodesList
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageData = extractPageData(response.bodyString()) ?: return emptyList()
        return parseEpisodes(pageData)
    }

    private fun parseEpisodes(pageData: InertiaPage): List<SEpisode> {
        val titleDetail = pageData.props.title ?: return emptyList()
        val loadedSeason = pageData.props.loadedSeason ?: return emptyList()
        val episodes = mutableListOf<SEpisode>()
        val seasonEpisodes = loadedSeason.episodes ?: emptyList()
        val seasonNumber = loadedSeason.number ?: 1
        for (ep in seasonEpisodes) {
            val epNumber = ep.number ?: 1
            val epId = ep.id ?: continue
            episodes.add(
                SEpisode.create().apply {
                    name = "S$seasonNumber:E$epNumber - ${ep.name ?: "Episode $epNumber"}"
                    episode_number = epNumber.toFloat()
                    setUrlWithoutDomain(
                        "/en/watch/${titleDetail.id}?episode_id=$epId&season=$seasonNumber",
                    )
                    scanlator = "S$seasonNumber"
                },
            )
        }
        return episodes
    }

    // ============================== Videos ================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val extractor = StreamingUnityExtractor(client, headers)

        val watchUrlPath = episode.url
        val titleId = watchUrlPath.removePrefix("/en/watch/").substringBefore("?").toLongOrNull()

        if (titleId == null) return emptyList()

        val iframeUrl = if (watchUrlPath.contains("episode_id=")) {
            val episodeId = watchUrlPath.substringAfter("episode_id=").substringBefore("&").toLongOrNull()
                ?: return emptyList()
            "$baseUrl/en/iframe/$titleId?episode_id=$episodeId"
        } else {
            "$baseUrl/en/iframe/$titleId"
        }

        return extractor.getVideos(iframeUrl)
    }

    // ============================== Helpers ===============================

    private fun cleanTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun extractPageData(html: String): InertiaPage? {
        val pattern = Pattern.compile(
            """data-page=(["'])(.*?)\1""",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return null

        val rawJson = Parser.unescapeEntities(matcher.group(2), false)

        return try {
            json.decodeFromString<InertiaPage>(rawJson)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStatus(status: String?): Int = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Returning", ignoreCase = true) ||
            status.contains("Ongoing", ignoreCase = true) ||
            status.contains("Continuing", ignoreCase = true) -> SAnime.ONGOING
        status.contains("Ended", ignoreCase = true) ||
            status.contains("Canceled", ignoreCase = true) ||
            status.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun getAnimeUrl(anime: SAnime): String = anime.url

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ============================== Config ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val popularPref = ListPreference(screen.context).apply {
            key = "popular"
            title = "Popular section"
            entries = arrayOf("Trending", "Latest", "Top 10")
            entryValues = arrayOf("trending", "latest", "top10")
            summary = "Which section to show as popular"
            setDefaultValue("trending")
        }
        screen.addPreference(popularPref)
    }

    override val versionId = 1
}

private fun TitleItem.toSAnime(): SAnime = SAnime.create().apply {
    title = name
    val cdnUrl = "https://cdn.streamingunity.dog"
    val posterImage = images.find { it.type == "poster" }
    thumbnail_url = posterImage?.let { "$cdnUrl/images/${it.filename}" }
    url = "/en/titles/$id-${slug ?: ""}"
}
