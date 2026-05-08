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

    override val name = "FaselHD"

    override val baseUrl = "https://faselhd.rip"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Use cloudflareClient to handle protection automatically
    override val client = network.cloudflareClient

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        element.select("div.imgdiv-class img").let {
            anime.title = it.attr("alt")
            anime.thumbnail_url = it.attr("data-src")
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.epAll a"

    private fun seasonsNextPageSelector(seasonNumber: Int) =
        "div#seasonList div.col-xl-2:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1
        
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "Watch"
            return episode
        }

        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                document.select("div.shortLink").map { episodes.add(episodeExtract(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    val seasonUrl = "$baseUrl/?p=" + it.select("div.seasonDiv")
                        .attr("onclick").substringAfterLast("=")
                        .substringBeforeLast("'")
                    addEpisodes(client.newCall(GET(seasonUrl, headers)).execute().asJsoup())
                }
            }
        }

        addEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        val seasonTitle = element.ownerDocument()!!.select("div.seasonDiv.active > div.title").text()
        episode.name = if (seasonTitle.isNotEmpty()) "$seasonTitle : ${element.text()}" else element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloatOrNull() ?: 0f
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "li:contains(سيرفر), li:contains(Server)"

    private val videoRegex by lazy { Regex("""(https?:)?//[^"]+\.m3u8""") }
    private val onClickRegex by lazy { Regex("""['"](https?://[^'"]+)['"]""") }

    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup().select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            val url = onClickRegex.find(element.attr("onclick"))?.groupValues?.get(1) ?: ""
            if (url.isNotEmpty()) {
                val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                val script = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")?.data()
                    ?.let(Deobfuscator::deobfuscateScript) ?: ""
                val playlist = videoRegex.find(script)?.value
                playlist?.let { playlistUtils.extractFromHls(it, referer = url) } ?: emptyList()
            } else {
                emptyList()
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment(categoryFilter.toUriPart())
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("Please select a section or category")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("meta[itemprop=name]").attr("content")
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a")
            .joinToString(", ") { it.text() }

        val cover = document.select("div.posterImg img.poster").attr("src")
        anime.thumbnail_url = if (cover.isNullOrEmpty()) {
            document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        } else {
            cover
        }
        anime.description = document.select("div.singleDesc").text()
        anime.status = parseStatus(document.select("span:contains(حالة)").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return if (statusString.contains("مستمر")) SAnime.ONGOING else SAnime.COMPLETED
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Section search (used when query is empty)"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Category filters (works if section is 'Default')"),
        CategoryFilter(),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter("Sections", arrayOf(
        Pair("Default", "none"),
        Pair("All Movies", "all-movies"),
        Pair("Series", "series"),
        Pair("Anime", "anime")
    ))

    private class CategoryFilter : PairFilter("Category", arrayOf(
        Pair("Default", "none"),
        Pair("Movies", "movies-cats"),
        Pair("Series", "series_genres")
    ))

    private class GenreFilter : SingleFilter("Genre", arrayOf(
        "Action", "Adventure", "Animation", "Comedy", "Drama", "Horror", "Romance", "Sci-fi", "Thriller"
    ).sortedArray())

    open class SingleFilter(name: String, private val vals: Array<String>) : AnimeFilter.Select<String>(name, vals) {
        fun toUriPart() = vals[state]
    }

    open class PairFilter(name: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ========================= Preferences ================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
                true
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
