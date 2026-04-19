package eu.kanade.tachiyomi.animeextension.ar.faselhd

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل HD"
    override val baseUrl = "https://www.faselhd.pro"
    override val lang = "ar"
    override val supportsLatest = true

    // ✅ SCREAMING_SNAKE_CASE للثوابت
    private companion object {
        const val POPULAR_SELECTOR = "div#postList div.col-xl-2 a"
        const val EPISODE_LIST_SELECTOR = "div.epAll a"
        const val VIDEO_LIST_SELECTOR = "li:contains(سيرفر)"
        const val NEXT_PAGE_SELECTOR = "ul.pagination li a.page-link:contains(›)"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val PREFERRED_QUALITY_KEY = "preferred_quality"
        
        private val VIDEO_URL_REGEX = Regex("""https?://[^"\s]+(?:m3u8|mp4)(?:[^\s"]*)?""", RegexOption.IGNORE_CASE)
        private val ONCLICK_URL_REGEX = Regex("""['"](https?://[^'"]+)['"]""")
        private val EPISODE_NUMBER_REGEX = Regex("""الحلقة\s*(\d+(?:\.\d+)?)""")
        
        private val SECTION_OPTIONS = arrayOf(
            "none", "anime-new", "anime-popular", "anime-ongoing", "anime-completed"
        )
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override val headers by lazy {
        super.headersBuilder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$baseUrl/")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = POPULAR_SELECTOR
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)
    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun popularAnimeFromElement(element: Element): SAnime = parseAnimeFromElement(element)

    // ============================== Latest ===============================
    override fun latestUpdatesSelector(): String = POPULAR_SELECTOR
    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR
    override fun latestUpdatesFromElement(element: Element): SAnime = parseAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    // ============================== Search ===============================
    override fun searchAnimeSelector(): String = POPULAR_SELECTOR
    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR
    override fun searchAnimeFromElement(element: Element): SAnime = parseAnimeFromElement(element)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=${URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())}", headers)
        } else {
            buildFilterRequest(page, filters)
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = EPISODE_LIST_SELECTOR

    override fun episodeListParse(response: Response): List<SEpisode> {
        return try {
            parseEpisodes(response.asJsoup())
        } catch (e: Exception) {
            response.asJsoup().select(EPISODE_LIST_SELECTOR).mapNotNull { safeEpisodeFromElement(it) }
        }
    }

    private fun parseEpisodes(document: Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1

        // الحلقات الحالية
        document.select(EPISODE_LIST_SELECTOR).mapNotNullTo(episodes) { safeEpisodeFromElement(it) }

        // Short links fallback
        if (episodes.isEmpty()) {
            document.select("div.shortLink").mapNotNullTo(episodes) { element ->
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(element.select("span#liskSh").text().trim())
                episode.name = "مشاهدة الحلقة"
                episode
            }
        }

        // المواسم التالية
        while (true) {
            val nextSeasonSelector = "div#seasonList div.col-xl-2:nth-child($seasonNumber)"
            val seasonElement = document.selectFirst(nextSeasonSelector) ?: break

            val seasonUrl = extractSeasonUrl(seasonElement)
            if (seasonUrl.isBlank()) break

            try {
                val nextDocument = client.newCall(GET("$baseUrl/?p=$seasonUrl", headers))
                    .execute().use { it.asJsoup() }
                nextDocument.select(EPISODE_LIST_SELECTOR).mapNotNullTo(episodes) { safeEpisodeFromElement(it) }
                seasonNumber++
            } catch (e: Exception) {
                break
            }
        }

        return episodes.reversed()
    }

    private fun safeEpisodeFromElement(element: Element): SEpisode? = try {
        episodeFromElement(element)
    } catch (e: Exception) {
        null
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(
            element.attr("abs:href").ifBlank { element.attr("href") }
        )

        val document = element.ownerDocument()
        val seasonTitle = document?.select("div.seasonDiv.active > div.title")?.text() ?: "الموسم"
        val episodeTitle = element.text().trim()

        episode.name = "$seasonTitle : $episodeTitle"

        EPISODE_NUMBER_REGEX.find(episodeTitle)?.groupValues?.getOrNull(0)
            ?.toFloatOrNull()?.let { episode.episode_number = it }

        return episode
    }

    private fun extractSeasonUrl(element: Element): String {
        return element.select("div.seasonDiv").attr("onclick")
            .substringAfterLast("=").substringBeforeLast("'").trim()
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = VIDEO_LIST_SELECTOR

    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup()
            .select(videoListSelector())
            .parallelCatchingFlatMapBlocking { element -> extractVideosFromElement(element) }
            .sorted()
    }

    private fun extractVideosFromElement(element: Element): List<Video> = try {
        val onclickAttr = element.attr("onclick")
        val serverUrl = ONCLICK_URL_REGEX.find(onclickAttr)?.value ?: return emptyList()

        client.newCall(GET(serverUrl, headers)).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val document = response.asJsoup()
            val scriptContent = document.selectFirst("script:containsData(video), script:containsData(mainPlayer)")
                ?.data()?.let(Deobfuscator::deobfuscateScript) ?: return emptyList()

            VIDEO_URL_REGEX.findAll(scriptContent)
                .mapNotNull { matchResult -> 
                    try {
                        playlistUtils.extractFromHls(matchResult.value).firstOrNull()
                    } catch (e: Exception) {
                        null
                    }
                }
                .toList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREFERRED_QUALITY_KEY, "1080") ?: "1080"
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { parseQualityNumber(it.quality) }
        )
    }

    private fun parseQualityNumber(quality: String): Int {
        return quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "عنوان غير معروف"

        anime.thumbnail_url = document.select("div.posterImg img.poster").attr("src").ifBlank {
            document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        }

        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a")
            .joinToString(", ") { it.text().trim() }

        anime.description = document.select("div.singleDesc").text().ifBlank { "لا يوجد وصف" }

        val statusText = document.select("span:contains(حالة)").text()
            .replace("حالة ", "").replace("المسلسل : ", "").trim()
        anime.status = parseStatus(statusText)

        return anime
    }

    private fun parseStatus(statusText: String): Int = when {
        statusText.contains("مستمر", ignoreCase = true) -> SAnime.ONGOING
        statusText.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================ Filters =============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("🔍 الفلاتر والأقسام"),
        SectionFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        GenreFilter()
    )

    private fun buildFilterRequest(page: Int, filters: AnimeFilterList): Request {
        val sectionFilter = filters.find { it is SectionFilter } as? SectionFilter
            ?: throw Exception("يرجى اختيار قسم")

        val urlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw Exception("رابط الموقع غير صالح")

        SECTION_OPTIONS[sectionFilter.state].takeIf { it != "none" }?.let { section ->
            urlBuilder.addPathSegment(section)
        }

        return GET("${urlBuilder.addPathSegment("page").addPathSegment("$page").build()}", headers)
    }

    private fun parseAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))

        val imageElement = element.select("div.imgdiv-class img").firstOrNull()
            ?: element.select("img").firstOrNull()
        
        anime.title = imageElement?.attr("alt") ?: "عنوان غير معروف"
        anime.thumbnail_url = imageElement?.attr("data-src") ?: imageElement?.attr("src") ?: ""

        return anime
    }

    // ==================== Filters Implementation ====================
    private class SectionFilter : AnimeFilter.Select<String>(
        "الأقسام",
        arrayOf("اختر", "الأنمي الجديد", "الأنمي الشائع", "الأنمي المستمر", "الأنمي المكتمل")
    )

    private class CategoryFilter : AnimeFilter.Select<String>(
        "النوع",
        arrayOf("الأنمي", "الأفلام", "المسلسلات")
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "التصنيف",
        arrayOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Romance", "Sci-Fi")
    )

    // ============================ Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREFERRED_QUALITY_KEY
            title = "جودة الفيديو المفضلة"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selectedValue = newValue as String
                val index = findIndexOfValue(selectedValue)
                summary = entries[index]
                preferences.edit().putString(key, entryValues[index] as String).apply()
                true
            }
        }.also { screen.addPreference(it) }
    }
}
