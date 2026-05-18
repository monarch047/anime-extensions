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
        // Custom domain input — user types any URL
        val domainPref = EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Custom Domain"
            summary = "Enter any movie site URL (e.g. https://1flix.to)"
            dialogTitle = "Enter domain URL"
            dialogMessage = "Must use the same site template as DopeFlix/1flix.to"
            text = preferences.domainUrl
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue.toString().trim('/')
                baseUrl = url
                preferences.domainUrl = url
                docHeaders = newHeaders()
                true
            }
        }
        screen.addPreference(domainPref)

        // Pass through to base class for remaining settings (quality, subs, etc.)
        screen.addListPreference(
            key = PREF_POPULAR_TYPE_KEY,
            title = PREF_POPULAR_TYPE_TITLE,
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_ENTRIES,
            default = PREF_POPULAR_TYPE_DEFAULT.value,
            summary = "%s",
        ) {
            preferences.prefPopularType = it
        }

        screen.addListPreference(
            key = PREF_LATEST_PRIORITY_KEY,
            title = PREF_LATEST_PRIORITY_TITLE,
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_ENTRIES,
            default = PREF_LATEST_PRIORITY_DEFAULT.value,
            summary = "%s",
        ) {
            preferences.prefLatestPriority = it
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_LIST,
            entryValues = PREF_QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefQuality = it
        }

        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = PREF_SUB_TITLE,
            entries = PREF_SUB_LANGUAGES,
            entryValues = PREF_SUB_LANGUAGES,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefSubtitle = it
        }

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = listOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"),
            entryValues = listOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"),
            default = "UpCloud",
            summary = "%s",
        ) {
            preferences.prefServer = it
        }

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = listOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"),
            entryValues = listOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"),
            default = setOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"),
        ) {
            preferences.hostToggle = it.toMutableSet()
        }
    }

    /** Allow any domain — don't restrict to domainList */
    override fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        return this
    }

    override var SharedPreferences.domainUrl
        by LazyMutable { preferences.getString(PREF_DOMAIN_KEY, "https://1flix.to")!! }

    override var SharedPreferences.hostToggle: MutableSet<String>
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, setOf("UpCloud", "MegaCloud", "Vidcloud", "AKCloud"))!! }
}
