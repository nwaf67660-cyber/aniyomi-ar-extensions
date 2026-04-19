package eu.kanade.tachiyomi.animeextension.ar.faselhd

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import java.util.concurrent.TimeUnit

/**
 * ملحق محسن لموقع فاصل HD - دعم كامل للأنمي والمسلسلات
 */
class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل HD ✅"
    override val baseUrl = "https://www.faselhd.pro"
    override val lang = "ar"
    override val supportsLatest = true

    // User-Agent محسن لتجنب الحظر
    private val userAgent by lazy {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Constants للـ Selectors لتسهيل الصيانة
    companion object {
        private const val POPULAR_SELECTOR = "div#postList div.col-xl-2 a"
        private const val EPISODE_LIST_SELECTOR = "div.epAll a"
        private const val VIDEO_LIST_SELECTOR = "li:contains(سيرفر)"
        private const val NEXT_PAGE_SELECTOR = "ul.pagination li a.page-link:contains(›)"
        
        // Regex محسن مع fallback
        private val VIDEO_URL_REGEX = Regex("""https?://[^"\s]+(?:m3u8|mp4)(?:[^\s"]*)?""", RegexOption.IGNORE_CASE)
        private val ONCLICK_URL_REGEX = Regex("""['"](https?://[^'"]+)['"]""")
        private val EPISODE_NUMBER_REGEX = Regex("""الحلقة\s*(\d+(?:\.\d+)?)""")
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", baseUrl)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Connection", "keep-alive")

    // ============================== Popular ===============================
    
    override fun popularAnimeSelector() = POPULAR_SELECTOR

    override fun popularAnimeRequest(page: Int): Request {
        require(page in 1..50) { "الصفحة يجب أن تكون بين 1-50" }
        return GET("$baseUrl/anime/page/$page", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = parseAnimeFromElement(element)

    override fun popularAnimeNextPageSelector() = NEXT_PAGE_SELECTOR

    // ============================== Latest ===============================
    
    override fun latestUpdatesSelector() = POPULAR_SELECTOR
    override fun latestUpdatesNextPageSelector() = NEXT_PAGE_SELECTOR
    override fun latestUpdatesFromElement(element: Element) = parseAnimeFromElement(element)
    
    override fun latestUpdatesRequest(page: Int): Request {
        require(page in 1..50) { "الصفحة يجب أن تكون بين 1-50" }
        return GET("$baseUrl/most_recent/page/$page", headers)
    }

    // ============================== Search ===============================
    
    override fun searchAnimeSelector() = POPULAR_SELECTOR
    override fun searchAnimeNextPageSelector() = NEXT_PAGE_SELECTOR

    override fun searchAnimeFromElement(element: Element) = parseAnimeFromElement(element)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> {
                require(page in 1..20) { "الصفحة يجب أن تكون بين 1-20" }
                GET("$baseUrl/page/$page?s=${query.trim().urlEncode()}", headers)
            }
            else -> buildFilterRequest(page, filters)
        }
    }

    // ============================== Episodes ==============================
    
    override fun episodeListSelector() = EPISODE_LIST_SELECTOR

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return try {
            parseEpisodesRecursively(document)
        } catch (e: Exception) {
            document.select(EPISODE_LIST_SELECTOR).mapNotNull { episodeFromElementSafe(it) }
        }
    }

    private fun parseEpisodesRecursively(document: Document, seasonNumber: Int = 1): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        
        // الحلقات المباشرة
        document.select(EPISODE_LIST_SELECTOR).mapNotNullTo(episodes) { episodeFromElementSafe(it) }
        
        // حلقات shortLink كبديل
        if (episodes.isEmpty()) {
            document.select("div.shortLink").mapNotNullTo(episodes) { parseShortLinkEpisode(it) }
        }
        
        // الموسم التالي
        val nextSeasonSelector = "div#seasonList div.col-xl-2:nth-child($seasonNumber)"
        document.selectFirst(nextSeasonSelector)?.let { seasonElement ->
            val nextSeasonUrl = extractSeasonUrl(seasonElement)
            if (nextSeasonUrl.isNotBlank()) {
                try {
                    val nextDoc = client.newCall(GET("$baseUrl/?p=$nextSeasonUrl", headers))
                        .execute()
                        .use { it.asJsoup() }
                    episodes += parseEpisodesRecursively(nextDoc, seasonNumber + 1)
                } catch (e: Exception) {
                    // تجاهل أخطاء المواسم اللاحقة
                }
            }
        }
        
        return episodes.reversed()
    }

    private fun episodeFromElementSafe(element: Element): SEpisode? = try {
        episodeFromElement(element)
    } catch (e: Exception) {
        null
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href").takeIf { it.isNotBlank() }
            ?: element.attr("href"))
        
        val titleElement = element.ownerDocument()?.select("div.seasonDiv.active > div.title")
        val seasonTitle = titleElement?.text()?.takeIf { it.isNotBlank() } ?: "الموسم"
        val episodeTitle = element.text().trim()
        
        episode.name = "$seasonTitle : $episodeTitle"
        
        // استخراج رقم الحلقة بأمان
        EPISODE_NUMBER_REGEX.find(episodeTitle)?.groupValues?.firstOrNull()?.toFloatOrNull()
            ?.let { episode.episode_number = it }
        
        return episode
    }

    private fun parseShortLinkEpisode(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("span#liskSh").text().trim())
        episode.name = "مشاهدة الحلقة"
        return episode
    }

    private fun extractSeasonUrl(element: Element): String {
        return element.select("div.seasonDiv")
            .attr("onclick")
            .substringAfterLast("=")
            .substringBeforeLast("'")
            .trim()
            .takeIf { it.isNotBlank() } ?: ""
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = VIDEO_LIST_SELECTOR

    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup()
            .select(videoListSelector())
            .parallelCatchingFlatMapBlocking { element ->
                extractVideosFromServer(element)
            }
            .sorted()
    }

    private fun extractVideosFromServer(element: Element): List<Video> {
        return try {
            val serverUrl = ONCLICK_URL_REGEX.find(element.attr("onclick"))?.value
                ?: return emptyList()
            
            client.newCall(GET(serverUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                
                val doc = response.asJsoup()
                val scriptContent = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")
                    ?.data()?.let(Deobfuscator::deobfuscateScript) ?: ""
                
                VIDEO_URL_REGEX.findAll(scriptContent)
                    .mapNotNull { extractVideoFromUrl(it.value) }
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVideoFromUrl(url: String): Video? {
        return try {
            val videos = playlistUtils.extractFromHls(url)
            videos.firstOrNull()?.copy(quality = normalizeQuality(videos.first().quality))
        } catch (e: Exception) {
            null
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString("preferred_quality", "1080") ?: "1080"
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { parseQualityNumber(it.quality) }
        )
    }

    private fun normalizeQuality(quality: String) = quality.trim()
        .replace(Regex("[^0-9]"), "")
        .takeIf { it.isNotBlank() && it.toIntOrNull() != null }
        ?: "720"

    private fun parseQualityNumber(quality: String): Int {
        return quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        
        // العنوان
        anime.title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "عنوان غير معروف"
        
        // الصورة المصغرة
        anime.thumbnail_url = selectThumbnail(document)
        
        // التصنيفات
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a")
            .mapNotNull { it.text().trim().takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString(", ")
        
        // الوصف
        anime.description = document.select("div.singleDesc").text()
            .takeIf { it.isNotBlank() } ?: "لا يوجد وصف"
        
        // الحالة
        anime.status = parseStatus(document.select("span:contains(حالة)").text())
        
        return anime
    }

    private fun selectThumbnail(document: Document): String {
        return document.select("div.posterImg img.poster").attr("src").takeIf { it.isNotBlank() }
            ?: document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
            ?: document.select("img[alt*='poster'], img.poster").attr("src")
            ?: ""
    }

    private fun parseStatus(statusText: String): Int {
        val cleanStatus = statusText.replace("حالة ", "").replace("المسلسل : ", "").trim()
        return when {
            cleanStatus.contains("مستمر", ignoreCase = true) -> SAnime.ONGOING
            cleanStatus.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("🔍 البحث بالكلمات أو الفلاتر"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("⚙️ الفلاتر تعمل فقط بدون كلمات بحث"),
        CategoryFilter(),
        GenreFilter(),
    )

    // ... باقي الفلاتر كما هي مع تحسينات طفيفة ...

    private fun buildFilterRequest(page: Int, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as? SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as? CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter

        require(!(sectionFilter?.state == 0 && categoryFilter?.state == 0)) {
            "من فضلك اختر قسم أو نوع"
        }

        return baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .apply {
                if (sectionFilter?.state != 0) {
                    addPathSegment(sectionFilter.toUriPart())
                } else if (categoryFilter?.state != 0) {
                    addPathSegment(categoryFilter.toUriPart())
                    genreFilter?.let { addPathSegment(it.toUriPart().lowercase()) }
                }
            }
            .addPathSegment("page")
            .addPathSegment("$page")
            .build()
            .let { GET(it.toString(), headers) }
    }

    private fun parseAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        
        val imgElement = element.select("div.imgdiv-class img").firstOrNull()
            ?: element.select("img").firstOrNull()
        
        anime.title = imgElement?.attr("alt") ?: "عنوان غير معروف"
        anime.thumbnail_url = imgElement?.attr("data-src") 
            ?: imgElement?.attr("src") ?: ""
        
        return anime
    }

    private fun String.urlEncode() = java.net.URLEncoder.encode(this, "UTF-8")

    // ============================ Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "🎥 جودة الفيديو المفضلة"
            entries = arrayOf("أفضل جودة", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("auto", "1080", "720", "480", "360")
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
