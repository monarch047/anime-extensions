package eu.kanade.tachiyomi.animeextension.en.homeflix

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix
import keiyoushi.utils.LazyMutable

class HomeFlix :
    DopeFlix(
        name = "HomeFlix",
        lang = "en",
        megaCloudApi = BuildConfig.MEGACLOUD_API,
        domainList = listOf("1flix.to"),
    ) {

    override val detailInfoSelector by lazy { "div.detail_page-infor, div.m_i-detail" }
    override val coverSelector by lazy { "div.cover_follow, div.dp-w-cover, div.w_b-cover" }
    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = EditTextPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Custom Domain"
            summary = "Enter any movie site URL (e.g. https://1flix.to)"
            dialogTitle = "Enter domain URL"
            dialogMessage = "Site must use same HTML template as 1flix.to / DopeFlix network"
            text = preferences.domainUrl
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue.toString().trim('/')
                baseUrl = url
                preferences.edit().putString("preferred_domain", url).apply()
                docHeaders = newHeaders()
                true
            }
        }
        screen.addPreference(domainPref)
    }

    /** Allow any domain */
    override fun SharedPreferences.clearOldPrefs(): SharedPreferences = this

    override var SharedPreferences.domainUrl
        by LazyMutable { preferences.getString("preferred_domain", "https://1flix.to")!! }

    override var SharedPreferences.hostToggle: MutableSet<String>
        by LazyMutable { preferences.getStringSet("hoster_selection", setOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"))!! }
}
