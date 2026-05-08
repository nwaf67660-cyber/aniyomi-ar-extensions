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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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

    override val baseUrl = "https://www.faselhd.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

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
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.epAll a"

    private fun seasonsNextPageSelector(seasonNumber: Int) =
        "div#seasonList div.col-xl-2:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonCounter = 1

        fun extractEpisode(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "Watch"
            return episode
        }

        fun fetchEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isEmpty()) {
                document.select("div.shortLink").map { episodes.add(extractEpisode(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                document.selectFirst(seasonsNextPageSelector(seasonCounter))?.let {
                    seasonCounter++
                    val seasonUrl = "$baseUrl/?p=" + it.select("div.seasonDiv")
                        .attr("onclick").substringAfterLast("=")
                        .substringBeforeLast("'")
                    fetchEpisodes(client.newCall(GET(seasonUrl, headers)).execute().asJsoup())
                }
            }
        }

        fetchEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        val seasonName = element.ownerDocument()!!.select("div.seasonDiv.active > div.title").text()
        episode.name = "$seasonName : ${element.text()}"
        episode.episode_number = element.text().replace("الحلقة ", "").toFloatOrNull() ?: 0f
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null) {
            val iframeHeaders = headers.newBuilder()
                .set("Referer", response.request.url.toString())
                .build()
            
            try {
                val iframeDoc = client.newCall(GET(iframeSrc, iframeHeaders)).execute().asJsoup()
                val scriptData = iframeDoc.selectFirst("script:containsData(mainPlayer)")?.data()
                    ?.let(Deobfuscator::deobfuscateScript)

                if (scriptData != null && scriptData.contains("file")) {
                    val hlsUrl = scriptData.substringAfter("file").substringAfter("'").substringBefore("'")
                    videoList.addAll(playlistUtils.extractFromHls(hlsUrl, referer = iframeSrc))
                }
            } catch (e: Exception) { }
        }

        // Fixed Extractor references
        document.select("ul.serverList li, div.servers-menu a, .additional-servers a").forEach { element ->
            val serverUrl = element.attr("abs:data-url").ifEmpty { element.attr("abs:href") }
            
            if (serverUrl.contains("dood")) {
                DoodExtractor(client).videoFromUrl(serverUrl)?.let { videoList.add(it) }
            }
            if (serverUrl.contains("ok.ru")) {
                videoList.addAll(OkruExtractor(client).videosFromUrl(serverUrl))
            }
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img, img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img, img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun searchAnimeSelector(): String = "div#postList div.col-xl-2 a"

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
        anime.thumbnail_url = if (cover.isEmpty()) {
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
    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Section search (use when query is empty)"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Advanced filters (use only if section is 'Default')"),
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
        "Action", "Adventure", "Animation", "Comedy", "Drama", "Horror", "Romance"
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
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
