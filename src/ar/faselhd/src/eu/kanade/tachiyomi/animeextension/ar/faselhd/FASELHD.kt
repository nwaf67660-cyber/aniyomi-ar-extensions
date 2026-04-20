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
import eu.kanide.tachiyomi.util.asJsoup
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
import kotlin.random.Random
import kotlin.concurrent.thread

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل اعلاني"

    override val baseUrl = "https://www.faselhd.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // User-Agent كما هو بالضبط
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0;Win64;x64;rv:136.0)Gecko"

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", userAgent)
            .add("Referer", baseUrl)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("DNT", "1")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Cache-Control", "max-age=0")
    }

    override fun createClient() = super.createClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // دالة الانتظار محسّنة لـ Kotlin
    private fun delayRequest() {
        Thread.sleep(2000 + Random.nextLong(0, 1000))
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request {
        delayRequest()
        return GET("$baseUrl/anime/page/$page", headers)
    }

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
        delayRequest()
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
                document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    val nextUrl = "$baseUrl/?p=" + it.select("div.seasonDiv")
                        .attr("onclick").substringAfterLast("=")
                        .substringBeforeLast("'")
                    delayRequest()
                    addEpisodes(
                        client.newCall(GET(nextUrl, headers)).execute().asJsoup()
                    )
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
        episode.episode_number = element.text().replace("الحلقة ", "").toFloat()
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "li:contains(سيرفر)"

    private val videoRegex by lazy { Regex("""(https?:)?//[^"]+\.m3u8""") }
    private val onClickRegex by lazy { Regex("""['"](https?://[^'"]+)['"]""") }

    override fun videoListParse(response: Response): List<Video> {
        delayRequest()
        return response.asJsoup().select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            try {
                val url = onClickRegex.find(element.attr("onclick"))?.groupValues?.get(1) ?: ""
                if (url.isEmpty()) return@parallelCatchingFlatMapBlocking emptyList()
                
                delayRequest()
                val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                val script = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer)")?.data()
                    ?.let(Deobfuscator::deobfuscateScript) ?: ""
                val playlist = videoRegex.find(script)?.value
                playlist?.let { playlistUtils.extractFromHls(it) } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // باقي الكود كما هو...
    // [كل باقي الدوال نفسها بدون تغيير]
    
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
        delayRequest()
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
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("meta
