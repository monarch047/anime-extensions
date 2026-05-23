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
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMapBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.parser.Parser
import rx.Observable
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

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.fromCallable {
            val response = try {
                client.newCall(animeDetailsRequest(anime)).execute()
            } catch (e: Exception) {
                throw e
            }

            if (response.code == 404) {
                response.close()
                // Recovery: search by name
                val searchRequest = searchAnimeRequest(1, anime.title, AnimeFilterList())
                val searchResponse = client.newCall(searchRequest).execute()
                val searchPage = searchAnimeParse(searchResponse)
                val matchedAnime = searchPage.animes.firstOrNull {
                    it.title.equals(anime.title, ignoreCase = true) ||
                        cleanTitle(it.title) == cleanTitle(anime.title)
                } ?: throw Exception("Title '${anime.title}' not found on site (404)")

                val recoveryResponse = client.newCall(GET("$baseUrl${matchedAnime.url}", headers)).execute()
                if (!recoveryResponse.isSuccessful) {
                    recoveryResponse.close()
                    throw Exception("HTTP error ${recoveryResponse.code} during recovery")
                }
                val details = animeDetailsParse(recoveryResponse)
                details.url = matchedAnime.url // Update the URL
                details
            } else {
                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("HTTP error ${response.code}")
                }
                animeDetailsParse(response)
            }
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
                append("\nType: ${titleDetail.type.uppercase()}")
                append("\nViews: ${titleDetail.views ?: "?"}")
                titleDetail.release_date?.let { append("\nRelease: $it") }
                titleDetail.last_air_date?.let { append("\nLast aired: $it") }
                titleDetail.age?.let { append("\nAge rating: $it+") }
            }
            status = parseStatus(titleDetail.status)
            genre = pageData.props.genres?.joinToString(", ") { it.name }

            val poster = titleDetail.images?.find { it.type == "poster" }
            thumbnail_url = poster?.let { "$cdnUrl/images/${it.filename}" }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return Observable.fromCallable {
            var url = anime.url
            var response = client.newCall(GET("$baseUrl$url", headers)).execute()

            if (response.code == 404) {
                response.close()
                // Recovery: search by name
                val searchRequest = searchAnimeRequest(1, anime.title, AnimeFilterList())
                val searchResponse = client.newCall(searchRequest).execute()
                val searchPage = searchAnimeParse(searchResponse)
                val matchedAnime = searchPage.animes.firstOrNull {
                    it.title.equals(anime.title, ignoreCase = true) ||
                        cleanTitle(it.title) == cleanTitle(anime.title)
                } ?: throw Exception("Title '${anime.title}' not found on site (404)")

                url = matchedAnime.url
                response = client.newCall(GET("$baseUrl$url", headers)).execute()
            }

            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP error ${response.code}")
            }

            val html = response.bodyString()
            val pageData = extractPageData(html) ?: throw Exception("Failed to parse page data")
            val titleDetail = pageData.props.title ?: throw Exception("Title details not found in page data")
            val loadedSeason = pageData.props.loadedSeason ?: throw Exception("No loaded season found")

            val episodesList = parseEpisodes(pageData).toMutableList()

            // Fetch other seasons in parallel using coroutine helper
            val otherSeasons = titleDetail.seasons.filter { it.number != loadedSeason.number }
            if (otherSeasons.isNotEmpty()) {
                val otherEpisodes = otherSeasons.parallelMapBlocking { season ->
                    val seasonUrl = "$baseUrl$url?season=${season.number}"
                    try {
                        val seasonResponse = client.newCall(GET(seasonUrl, headers)).execute()
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

            episodesList
        }
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
        for (ep in seasonEpisodes) {
            episodes.add(
                SEpisode.create().apply {
                    name = "S${loadedSeason.number}:E${ep.number} - ${ep.name ?: "Episode ${ep.number}"}"
                    episode_number = ep.number.toFloat()
                    setUrlWithoutDomain(
                        "/en/watch/${titleDetail.id}?episode_id=${ep.id}&season=${loadedSeason.number}",
                    )
                    scanlator = "S${loadedSeason.number}"
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
        val episodeId = watchUrlPath.substringAfter("episode_id=").substringBefore("&").toLongOrNull()

        if (titleId == null || episodeId == null) return emptyList()

        val iframeUrl = "$baseUrl/en/iframe/$titleId?episode_id=$episodeId"

        return extractor.getVideos(iframeUrl)
    }

    // ============================== Helpers ===============================

    private fun cleanTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun extractPageData(html: String): InertiaPage? {
        val pattern = Pattern.compile(
            """<div\s+id=["']app["']\s+data-page="(.*?)"\s*>""",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return null

        val rawJson = Parser.unescapeEntities(matcher.group(1), false)

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
    url = "/en/titles/$id-$slug"
}
