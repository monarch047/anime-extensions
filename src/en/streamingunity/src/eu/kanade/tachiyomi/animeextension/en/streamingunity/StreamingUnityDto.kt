package eu.kanade.tachiyomi.animeextension.en.streamingunity

import kotlinx.serialization.Serializable

/**
 * Top-level Inertia.js data-page JSON wrapper.
 */
@Serializable
data class InertiaPage(
    val component: String,
    val props: PageProps,
)

@Serializable
data class PageProps(
    val title: TitleDetail? = null,
    val sliders: List<Slider>? = null,
    val titles: List<TitleItem>? = null,
    val loadedSeason: Season? = null,
    val label: String? = null,
    val totalCount: Int? = null,
    val browseMoreApiRoute: String? = null,
    val noFilters: Boolean? = null,
    val scws_url: String = "https://vixcloud.co",
    val cdn_url: String = "https://cdn.streamingunity.dog",
    val app_url: String = "https://streamingunity.dog",
    val genres: List<Genre>? = null,
)

/**
 * Title detail from the Title page.
 */
@Serializable
data class TitleDetail(
    val id: Long,
    val name: String,
    val slug: String,
    val plot: String? = null,
    val quality: String? = null,
    val type: String, // "tv" or "movie"
    val original_name: String? = null,
    val status: String? = null,
    val views: Int? = null,
    val score: String? = null,
    val tmdb_id: Int? = null,
    val imdb_id: String? = null,
    val release_date: String? = null,
    val last_air_date: String? = null,
    val age: Int? = null,
    val seasons_count: Int? = null,
    val seasons: List<Season> = emptyList(),
    val trailers: List<Trailer>? = null,
)

/**
 * Season data.
 */
@Serializable
data class Season(
    val id: Long,
    val number: Int,
    val name: String? = null,
    val plot: String? = null,
    val release_date: String? = null,
    val title_id: Long? = null,
    val episodes_count: Int? = null,
    val episodes: List<Episode>? = null,
)

/**
 * Episode data.
 */
@Serializable
data class Episode(
    val id: Long,
    val number: Int,
    val name: String? = null,
    val plot: String? = null,
    val duration: Int? = null,
    val scws_id: Long? = null,
    val season_id: Long? = null,
    val quality: String? = null,
    val images: List<Image>? = null,
)

/**
 * Image data (for posters, covers, backgrounds).
 */
@Serializable
data class Image(
    val filename: String,
    val type: String, // "poster", "cover", "background", "logo", "cover_mobile"
    val lang: String? = null,
    val original_url_field: String? = null,
)

/**
 * Trailer data.
 */
@Serializable
data class Trailer(
    val id: Long,
    val name: String? = null,
    val youtube_id: String? = null,
)

/**
 * Slider section (homepage).
 */
@Serializable
data class Slider(
    val name: String, // "trending", "latest", "top10", "related"
    val label: String,
    val titles: List<TitleItem>,
)

/**
 * Title item in lists/search/sliders.
 */
@Serializable
data class TitleItem(
    val id: Long,
    val slug: String,
    val name: String,
    val type: String, // "tv" or "movie"
    val score: String? = null,
    val sub_ita: Int? = null,
    val last_air_date: String? = null,
    val age: Int? = null,
    val seasons_count: Int? = null,
    val images: List<Image> = emptyList(),
    val top10_index: Int? = null,
)

/**
 * Genre data.
 */
@Serializable
data class Genre(
    val id: Int,
    val name: String,
    val slug: String,
)
