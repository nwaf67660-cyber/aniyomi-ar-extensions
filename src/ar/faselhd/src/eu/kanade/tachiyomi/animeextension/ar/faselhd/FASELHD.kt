package eu.kanade.tachiyomi.animeextension.ar.faselhd

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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

    override val name = "فاصل HD ✅"
    override val baseUrl = "https://www.faselhd.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val userAgent by lazy {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private const val POPULAR_SELECTOR = "div#postList div.col-xl-2 a"
        private const val EPISODE_LIST_SELECTOR = "div.epAll a"
        private const val VIDEO_LIST_SELECTOR = "li:contains(سيرفر)"
        private const val NEXT_PAGE_SELECTOR = "ul.pagination li a.page-link:contains(›)"
        
        private val VIDEO_URL_REGEX = Regex("""https?://[^"\s]+(?:m3u8|mp4)(?:[^\s"]*)?""", RegexOption.IGNORE_CASE)
        private val ONCLICK_URL_REGEX = Regex("""['"](https?://[^'"]+)['"]""")
        private val EPISODE_NUMBER_REGEX = Regex("""الحلقة\s*(\d+(?:\.\d+)?)""")
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", baseUrl)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = POPULAR_SELECTOR
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)
    override fun popularAnimeFromElement(element: Element): SAnime = parseAnimeFromElement(element)
    override fun popularAnimeNextPageSelector() = NEXT_PAGE_SELECTOR

    // ============================== Latest ===============================
    override fun latestUpdatesSelector() = POPULAR_SELECTOR
    override fun latestUpdatesNextPageSelector() = NEXT_PAGE_SELECTOR
    override fun latestUpdatesFromElement(element: Element): SAnime = parseAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    // ============================== Search ===============================
    override fun searchAnimeSelector() = POPULAR_SELECTOR
    override fun searchAnimeNextPageSelector() = NEXT_PAGE_SELECTOR
    override fun searchAnimeFromElement(element: Element): SAnime = parseAnimeFromElement(element)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=${URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())}", headers)
        } else {
            buildFilterRequest(page, filters)
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = EPISODE_LIST_SELECTOR

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

        fun extractEpisode(element: Element): SEpisode? = safeEpisodeFromElement(element)

        // الحلقات الحالية
        document.select(EPISODE_LIST_SELECTOR).mapNotNullTo(episodes) { extractEpisode(it) }

        // Short links كبديل
        if (episodes.isEmpty()) {
            document.select("div.shortLink").mapNotNullTo(episodes) { 
                val ep = SEpisode.create()
                ep.setUrlWithoutDomain(it.select("span#liskSh").text().trim())
                ep.name = "مشاهدة الحلقة"
                ep
            }
        }

        // المواسم التالية
        while (true) {
            val nextSeasonSelector = "div#seasonList div.col-xl-2:nth-child($seasonNumber)"
            val seasonElement = document.selectFirst(nextSeasonSelector) ?: break
            
            val seasonUrl = seasonElement.select("div.seasonDiv")
                .attr("onclick")
                .let { onclick -> 
                    onclick.substringAfterLast("=").substringBeforeLast("'").trim() 
                }
            
            if (seasonUrl.isBlank()) break

            try {
                val nextDoc = client.newCall(GET("$baseUrl/?p=$seasonUrl", headers))
                    .execute().use { it.asJsoup() }
                nextDoc.select(EPISODE_LIST_SELECTOR).mapNotNullTo(episodes) { extractEpisode(it) }
                seasonNumber++
            } catch (e: Exception) {
                break
            }
        }

        return episodes.reversed()
    }

    private fun safeEpisodeFromElement(element: Element): SEpisode? = try {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(
            element.attr("abs:href").ifBlank { element.attr("href") }
        )
        
        val doc = element.ownerDocument()
        val seasonTitle = doc?.select("div.seasonDiv.active > div.title")?.text() ?: "الموسم"
        val epTitle = element.text().trim()
        
        episode.name = "$seasonTitle : $epTitle"
        
        EPISODE_NUMBER_REGEX.find(epTitle)?.groupValues?.getOrNull(0)
            ?.toFloatOrNull()?.let { episode.episode_number = it }
        
        episode
    } catch (e: Exception) {
        null
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = VIDEO_LIST_SELECTOR

    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup()
            .select(videoListSelector())
            .parallelCatchingFlatMapBlocking { element ->
                try {
                    val onclickUrl = ONCLICK_URL_REGEX.find(element.attr("onclick"))?.value ?: return@parallelCatchingFlatMapBlocking emptyList()
                    
                    client.newCall(GET(onclickUrl, headers)).execute().use { resp ->
                        if (!resp.isSuccessful) return@parallelCatchingFlatMapBlocking emptyList()
                        
                        val doc = resp.asJsoup()
                        val script = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")
                            ?.data()?.let(Deobfuscator::deobfuscateScript) ?: ""
                        
                        VIDEO_URL_REGEX.findAll(script)
                            .mapNotNull { urlMatch ->
                                try {
                                    playlistUtils.extractFromHls(urlMatch.value).firstOrNull()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            .toList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            .sorted()
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString("preferred_quality", "1080") ?: "1080"
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferred, ignoreCase = true) }
                .thenByDescending { it.quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
        )
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
        anime.status = when {
            statusText.contains("مستمر", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        
        return anime
    }

    // ============================ Filters =============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("🔍 استخدم الفلاتر أو البحث بالكلمات"),
        SectionFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        GenreFilter(),
    )

    private fun buildFilterRequest(page: Int, filters: AnimeFilterList): Request {
        val sectionFilter = filters.find { it is SectionFilter } as? SectionFilter
            ?: throw Exception("اختر قسم")
        
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment(sectionFilter.state.let { SectionFilter.options[it].second })
            .addPathSegment("page")
            .addPathSegment("$page")
            .toString()
        
        return GET(url, headers)
    }

    private fun parseAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        
        val img = element.select("div.imgdiv-class img").firstOrNull() ?: element.select("img").firstOrNull()
        anime.title = img?.attr("alt") ?: "عنوان غير معروف"
        anime.thumbnail_url = img?.attr("data-src") ?: img?.attr("src") ?: ""
        
        return anime
    }

    // ==================== Filters Implementation ====================
    private class SectionFilter : AnimeFilter.Select<String>(
        "الأقسام",
        arrayOf(
            "جميع الأنمي", "الأنمي الجديد", "الأنمي الشائع",
            "الأنمي المستمر", "الأنمي المكتمل"
        )
    ) {
        companion object {
            val options = arrayOf(
                "none", "anime-new", "anime-popular", "anime-ongoing", "anime-completed"
            )
        }
        fun toUriPart() = options[state]
    }

    private class CategoryFilter : AnimeFilter.Select<String>(
        "النوع",
        arrayOf("الأنمي", "الأفلام", "المسلسلات")
    ) {
        fun toUriPart() = when (state) {
            0 -> "anime"
            1 -> "movies"
            2 -> "series"
            else -> "anime"
        }
    }

    private class GenreFilter : AnimeFilter.Select<String>(
        "التصنيف",
        arrayOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Romance", "Sci-Fi")
    ) {
        fun toUriPart() = when (state) {
            0 -> "action"
            1 -> "adventure"
            2 -> "comedy"
            3 -> "drama"
            4 -> "fantasy"
            5 -> "horror"
            6 -> "romance"
            7 -> "scifi"
            else -> "action"
        }
    }

    // ============================ Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "🎥 الجودة المفضلة"
            entries = arrayOf("أعلى جودة", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "الحالي: %s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                summary = "الحالي: ${entries[index]}"
                preferences.edit().putString(key, entryValues[index] as String).apply()
                true
            }
        }.also { screen.addPreference(it) }
    }
}
