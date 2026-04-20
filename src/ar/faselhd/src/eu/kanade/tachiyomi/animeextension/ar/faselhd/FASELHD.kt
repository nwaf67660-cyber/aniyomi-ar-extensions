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
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل اعلاني"
    override val baseUrl = "https://www.faselhd.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    
    // Cloudflare bypass headers
    private val cfHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "ar,en;q=0.9,en-US;q=0.8")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("DNT", "1")
        .add("Connection", "keep-alive")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .add("Referer", baseUrl)
        .build()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            .add("Referer", baseUrl)
            .add("Accept-Language", "ar,en;q=0.9")
    }

    
    // Create request without cache
    private fun createNoCacheRequest(url: String): Request {
        val cacheControl = CacheControl.Builder()
            .noCache()
            .noStore()
            .maxAge(0, TimeUnit.SECONDS)
            .build()

        return GET(url, cfHeaders).newBuilder()
            .cacheControl(cacheControl)
            .build()
    }

    
    // Bypass Cloudflare protection
    private fun bypassCloudflare(url: String): Document? {
        return try {
            val initialResponse = client.newCall(createNoCacheRequest(url)).execute()
            val initialDoc = initialResponse.asJsoup()
            
            if (initialDoc.select("title").any { 
                it.text().contains("Checking your browser") || 
                it.text().contains("Just a moment") || 
                it.text().contains("cloudflare")
            }) {
                Thread.sleep(4000)
                
                val retryHeaders = cfHeaders.newBuilder()
                    .add("Sec-CH-UA", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                    .add("Sec-CH-UA-Mobile", "?0")
                    .add("Sec-CH-UA-Platform", "\"Windows\"")
                    .build()
                
                val retryRequest = createNoCacheRequest(url).newBuilder()
                    .headers(retryHeaders)
                    .build()
                
                val retryResponse = client.newCall(retryRequest).execute()
                if (retryResponse.isSuccessful) {
                    retryResponse.asJsoup()
                } else {
                    initialDoc
                }
            } else {
                initialDoc
            }
        } catch (e: Exception) {
            client.newCall(createNoCacheRequest(url)).execute().asJsoup()
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = createNoCacheRequest("$baseUrl/anime/page/$page")

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

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div#seasonList div.col-xl-2:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = bypassCloudflare(response.request.url.toString()) ?: response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1
        
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "مشاهدة"
            return episode
        }

        fun addEpisodes(doc: Document) {
            if (doc.select(episodeListSelector()).isNullOrEmpty()) {
                doc.select("div.shortLink").map { episodes.add(episodeExtract(it)) }
            } else {
                doc.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                doc.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    val seasonUrl = "$baseUrl/?p=" + it.select("div.seasonDiv")
                        .attr("onclick").substringAfterLast("=").substringBeforeLast("'")
                    val seasonDoc = bypassCloudflare(seasonUrl) ?: 
                        client.newCall(createNoCacheRequest(seasonUrl)).execute().asJsoup()
                    addEpisodes(seasonDoc)
                }
            }
        }

        addEpisodes(document)
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.ownerDocument()!!.select("div.seasonDiv.active > div.title")
            .text() + " : " + element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloat()
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "li:contains(سيرفر)"

    private val videoRegex by lazy { Regex("""(https?:)?//[^"]+\.m3u8""") }
    private val onClickRegex by lazy { Regex("""['"](https?://[^'"]+)['"]""") }

    override fun videoListParse(response: Response): List<Video> {
        val document = bypassCloudflare(response.request.url.toString()) ?: response.asJsoup()
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            val url = onClickRegex.find(element.attr("onclick"))?.groupValues?.get(1) ?: ""
            val videoDoc = bypassCloudflare(url) ?: 
                client.newCall(createNoCacheRequest(url)).execute().asJsoup()
            val script = videoDoc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")?.data()
                ?.let(Deobfuscator::deobfuscateScript) ?: ""
            val playlist = videoRegex.find(script)?.value
            playlist?.let { playlistUtils.extractFromHls(it) } ?: emptyList()
        }
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
            createNoCacheRequest("$baseUrl/page/$page?s=$query")
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment(categoryFilter.toUriPart())
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            createNoCacheRequest(url.toString())
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
        anime.status = parseStatus(
            document.select("span:contains(حالة)").text().replace("حالة ", "").replace("المسلسل : ", "")
        )
        return anime
    }

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "مستمر" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
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

    override fun latestUpdatesRequest(page: Int): Request = createNoCacheRequest("$baseUrl/most_recent/page/$page")
    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

    // ============================ Filters =============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارغ"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("الفلترة تعمل فقط لو كان اقسام الموقع على 'اختر'"),
        CategoryFilter(),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("جميع الافلام", "all-movies"),
            Pair("افلام اجنبي", "movies"),
            Pair("افلام مدبلجة", "dubbed-movies"),
            Pair("افلام هندي", "hindi"),
            Pair("افلام اسيوي", "asian-movies"),
            Pair("افلام انمي", "anime-movies"),
            Pair("الافلام الاعلي تصويتا", "movies_top_votes"),
            Pair("الافلام الاعلي مشاهدة", "movies_top_views"),
            Pair("الافلام الاعلي تقييما IMDB", "movies_top_imdb"),
            Pair("جميع المسلسلات", "series"),
            Pair("مسلسلات الأنمي", "anime"),
            Pair("المسلسلات الاعلي تقييما IMDB", "series_top_imdb"),
            Pair("المسلسلات القصيرة", "short_series"),
            Pair("المسلسلات الاسيوية", "asian-series"),
            Pair("المسلسلات الاعلي مشاهدة", "series_top_views"),
            Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
            Pair("الانمي الاعلي مشاهدة", "anime_top_views"),
            Pair("البرامج التليفزيونية", "tvshows"),
            Pair("البرامج التليفزيونية الاعلي مشاهدة", "tvshows_top_views"),
        ),
    )

    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies-cats"),
            Pair("مسلسلات", "series_genres"),
            Pair("انمى", "anime-cats"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action", "Adventure", "Animation", "Western", "Sport", "Short",
            "Documentary", "Fantasy", "Sci-fi", "Romance", "Comedy", "Family",
            "Drama", "Thriller", "Crime", "Horror", "Biography",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // preferred quality settings
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
