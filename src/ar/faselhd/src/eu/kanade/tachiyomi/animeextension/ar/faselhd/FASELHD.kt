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

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل HD"
    override val baseUrl = "https://www.faselhd.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", baseUrl)
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)
    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        element.select("div.imgdiv-class img").let {
            anime.title = it.attr("alt")
            anime.thumbnail_url = it.attr("data-src")
        }
        return anime
    }

    // ============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"
    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    // ============================== Search ===============================
    override fun searchAnimeSelector(): String = "div#postList div.col-xl-2 a"
    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.epAll a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1
        
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "مشاهدة"
            return episode
        }

        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                document.select("div.shortLink").map { episodes.add(episodeExtract(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                document.selectFirst("div#seasonList div.col-xl-2:nth-child($seasonNumber)")?.let {
                    seasonNumber++
                    val seasonUrl = it.select("div.seasonDiv").attr("onclick")
                        .substringAfterLast("=").substringBeforeLast("'")
                    client.newCall(GET("$baseUrl/?p=$seasonUrl", headers))
                        .execute().use { addEpisodes(it.asJsoup()) }
                }
            }
        }

        addEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.ownerDocument()!!.select("div.seasonDiv.active > div.title")
            .text() + " : " + element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloatOrNull() ?: 1f
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "li:contains(سيرفر)"

    private val videoRegex by lazy { Regex("""(https?:)?//[^"]+\.m3u8""", RegexOption.IGNORE_CASE) }
    private val onClickRegex by lazy { Regex("""['"](https?://[^'"]+)['"]""") }

    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup().select(videoListSelector())
            .parallelCatchingFlatMapBlocking { element ->
                val url = onClickRegex.find(element.attr("onclick"))?.groupValues?.getOrNull(1) ?: ""
                if (url.isEmpty()) return@parallelCatchingFlatMapBlocking emptyList()
                
                client.newCall(GET(url, headers)).execute().use { response ->
                    val doc = response.asJsoup()
                    val script = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")
                        ?.data()?.let(Deobfuscator::deobfuscateScript) ?: ""
                    
                    val playlist = videoRegex.find(script)?.value
                    playlist?.let { playlistUtils.extractFromHls(it) } ?: emptyList()
                }
            }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080") ?: "1080"
        return sortedWith(compareByDescending { it.quality.contains(quality, true) })
            .thenByDescending { it.quality.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "عنوان غير معروف"
        
        anime.thumbnail_url = document.select("div.posterImg img.poster").attr("src").takeIf { it.isNotBlank() }
            ?: document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        
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
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("🔍 الفلاتر"),
        SectionFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
        GenreFilter()
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=${query.trim()}", headers)
        } else {
            val filterList = filters.ifEmpty { getFilterList() }
            val sectionFilter = filterList.find { it is SectionFilter } as? SectionFilter
            val url = "$baseUrl/".toHttpUrlOrNull()?.newBuilder() ?: return GET(baseUrl, headers)
            
            sectionFilter?.state?.takeIf { it > 0 }?.let { 
                url.addPathSegment(SECTION_OPTIONS[it])
            }
            
            GET(url.addPathSegment("page").addPathSegment(page.toString()).toString(), headers)
        }
    }

    // Filters Classes (مبسطة)
    private class SectionFilter : AnimeFilter.Select<String>(
        "الأقسام", arrayOf("اختر", "anime", "anime-new", "anime-popular")
    )

    private class CategoryFilter : AnimeFilter.Select<String>(
        "النوع", arrayOf("اختر", "الأنمي", "الأفلام", "المسلسلات")
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "التصنيف", arrayOf("Action", "Adventure", "Comedy", "Drama")
    )

    companion object {
        private val SECTION_OPTIONS = arrayOf("none", "anime", "anime-new", "anime-popular")
    }

    // ============================ Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "جودة الفيديو المفضلة"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                preferences.edit().putString(key, entryValues[index] as String).apply()
                summary = entries[index]
                true
            }
        }.also { screen.addPreference(it) }
    }
}
