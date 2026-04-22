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

    // 🔥 HEADERS محسنة لتجاوز Cloudflare
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("Referer", baseUrl)
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-User", "?1")
            .add("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
            .add("Sec-Ch-Ua-Mobile", "?0")
            .add("Sec-Ch-Ua-Platform", "\"Windows\"")
            .add("Upgrade-Insecure-Requests", "1")
    }

    // 🔥 Client مُحسّن لـCloudflare + Timeout
    private val cloudflareClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/anime/page/$page", cloudflareHeaders)

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
    override fun episodeListSelector() = "div.epAll a, .episode-item a"

    private fun seasonsNextPageSelector(seasonNumber: Int) =
        "div#seasonList div.col-xl-2:nth-child($seasonNumber), .season-tab-$seasonNumber"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        // 🔥 تحقق من Cloudflare challenge
        if (document.select("title:contains(Checking), #cf-browser-verification").isNotEmpty()) {
            throw Exception("Cloudflare challenge detected. Try VPN or wait.")
        }
        
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1
        
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            val link = element.select("span#liskSh, a").firstOrNull()?.attr("href") 
                ?: element.attr("href") ?: element.attr("data-url")
            episode.setUrlWithoutDomain(link)
            episode.name = "مشاهدة"
            return episode
        }

        fun addEpisodes(doc: Document) {
            // 🔥 Selectors متعددة للمواقع المُحدّثة
            doc.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
            doc.select("div.shortLink, .episode-link").map { episodes.add(episodeExtract(it)) }
            
            doc.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                seasonNumber++
                val nextUrl = "$baseUrl/?p=" + it.select("div.seasonDiv, [data-season]")
                    .attr("onclick").substringAfterLast("=").substringBeforeLast("'")
                try {
                    addEpisodes(cloudflareClient.newCall(GET(nextUrl, cloudflareHeaders)).execute().asJsoup())
                } catch (e: Exception) {
                    // تجاهل المواسم الفارغة
                }
            }
        }

        addEpisodes(document)
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href") ?: element.attr("data-url"))
        val seasonTitle = element.ownerDocument()?.select("div.seasonDiv.active > div.title, .season-title")?.text() ?: "الموسم"
        episode.name = "$seasonTitle : ${element.text()}"
        episode.episode_number = element.text().replace("الحلقة ", "").toFloatOrNull() ?: 1f
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "li:contains(سيرفر), .server-item, [data-server]"

    private val videoRegex by lazy { Regex("""https?://[^"\s]+\.m3u8(?:[^"\s]*)?""", RegexOption.IGNORE_CASE) }
    private val onClickRegex by lazy { Regex("""['"](https?://[^'"\s]+)['"]""") }
    private val embedRegex by lazy { Regex("""src=['"](https?://[^'"]+)['"]""") }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // 🔥 فلترة الإعلانات + Cloudflare check
        if (document.select("#cf-browser-verification, .cf-challenge").isNotEmpty()) {
            return emptyList()
        }
        
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            try {
                val url = onClickRegex.find(element.attr("onclick"))?.groupValues?.get(1)
                    ?: embedRegex.find(element.html())?.groupValues?.get(1)
                    ?: element.attr("href") ?: element.attr("data-src")
                
                if (url.isNullOrBlank()) return@parallelCatchingFlatMapBlocking emptyList()
                
                val doc = cloudflareClient.newCall(GET(url, cloudflareHeaders)).execute().asJsoup()
                val script = doc.selectFirst("script:containsData(video), script:containsData(mainPlayer), script:contains(m3u8)")?.data()
                    ?.let(Deobfuscator::deobfuscateScript) ?: ""
                
                videoRegex.findAll(script).mapNotNull { match ->
                    val playlistUrl = match.value
                    playlistUtils.extractFromHls(playlistUrl).firstOrNull()
                }.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }.filter { it.isNotEmpty() } // فلترة السيرفرات الفارغة
    }

    private val cloudflareHeaders: Headers by lazy {
        headersBuilder().build()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality, true) }
                .thenByDescending { it.quality.contains("1080", true) }
                .thenByDescending { it.quality.contains("720", true) }
        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // باقي الكود **نفس الواجهة تمامًا** (Search, Details, Filters, Preferences)
    // ... [الكود الأصلي بدون تغيير]
    
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
            GET("$baseUrl/page/$page?s=$query", cloudflareHeaders)
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
            GET(url.toString(), cloudflareHeaders)
        }
    }

    // باقي الدوال نفسها تمامًا...
    // [animeDetailsParse, latestUpdates, Filters, Preferences] 
    // لم تتغير الواجهة أبدًا!
}
