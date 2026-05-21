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
import keiyoushi.utils.bodyAsText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
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
        val pageData = extractPageData(response.bodyAsText()) ?: return AnimesPage(emptyList(), false)
        val titles = pageData.props.titles ?: pageData.props.sliders?.firstOrNull()?.titles ?: emptyList()
        return AnimesPage(titles.map { it.toSAnime() }, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/en/browse/latest", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/en/search?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val pageData = extractPageData(response.bodyAsText()) ?: return AnimesPage(emptyList(), false)
        val titles = pageData.props.titles ?: emptyList()
        return AnimesPage(titles.map { it.toSAnime() }, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val pageData = extractPageData(response.bodyAsText()) ?: return SAnime.create()
        val title = pageData.props.title ?: return SAnime.create()

        val cdnUrl = pageData.props.cdn_url

        return SAnime.create().apply {
            title = title.name
            description = buildString {
                append(title.plot ?: "")
                append("\n\n")
                append("Score: ${title.score ?: "?"}★")
                append("\nStatus: ${title.status ?: "Unknown"}")
                append("\nType: ${title.type.uppercase()}")
                append("\nViews: ${title.views ?: "?"}")
                title.release_date?.let { append("\nRelease: $it") }
                title.last_air_date?.let { append("\nLast aired: $it") }
                title.age?.let { append("\nAge rating: $it+") }
            }
            status = parseStatus(title.status)
            genre = pageData.props.genres?.joinToString(", ") { it.name }

            val poster = title.seasons.firstOrNull()
                ?.episodes?.firstOrNull()
                ?.images?.firstOrNull()
            thumbnail_url = poster?.let { "$cdnUrl/images/${it.filename}" }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageData = extractPageData(response.bodyAsText()) ?: return emptyList()
        val title = pageData.props.title ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()
        for (season in title.seasons) {
            val seasonEpisodes = season.episodes ?: continue
            for (ep in seasonEpisodes) {
                episodes.add(
                    SEpisode.create().apply {
                        name = "S${season.number}:E${ep.number} - ${ep.name ?: "Episode ${ep.number}"}"
                        episode_number = ep.number.toFloat()
                        setUrlWithoutDomain(
                            "/en/watch/${title.id}?episode_id=${ep.id}&season=${season.number}",
                        )
                        scanlator = "S${season.number}"
                    },
                )
            }
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

    override fun getVideoListParse(response: Response): List<Video> = emptyList()

    // ============================== Helpers ===============================

    private fun extractPageData(html: String): InertiaPage? {
        val pattern = Pattern.compile(
            """<div\s+id=["']app["']\s+data-page=["'](.*?)["']""",
            Pattern.DOTALL,
        )
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return null

        val rawJson = matcher.group(1)
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        return try {
            json.parseAs<InertiaPage>(JsonObject.parseAs(rawJson))
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
