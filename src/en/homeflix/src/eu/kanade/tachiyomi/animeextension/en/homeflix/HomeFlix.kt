package eu.kanade.tachiyomi.animeextension.en.homeflix

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class HomeFlix :
    DopeFlix(
        name = "HomeFlix",
        lang = "en",
        megaCloudApi = BuildConfig.MEGACLOUD_API,
        defaultDomain = "https://1flix.to",
    ) {

    override val detailInfoSelector by lazy { "div.detail_page-infor, div.m_i-detail" }
    override val coverSelector by lazy { "div.cover_follow, div.dp-w-cover, div.w_b-cover" }
    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
}
