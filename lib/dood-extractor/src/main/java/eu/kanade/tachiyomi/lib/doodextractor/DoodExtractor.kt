package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI

class DoodExtractor(private val client: OkHttpClient) {

    // قائمة الهويات العشوائية لتوحيد البصمة مع الكلاود فلير
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )

    fun videoFromUrl(
        url: String,
        prefix: String? = null,
        redirect: Boolean = true,
        externalSubs: List<Track> = emptyList(),
    ): Video? {
        return runCatching {
            // استخدام هوية عشوائية للطلب الأول
            val selectedUA = USER_AGENTS.random()
            val response = client.newCall(GET(url, Headers.headersOf("User-Agent", selectedUA))).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body.string()
            if (!content.contains("'/pass_md5/")) return null

            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            val newQuality = listOfNotNull(
                prefix,
                "Doodstream " + (extractedQuality ?: ( if (redirect) "mirror" else "")),
            ).joinToString(" - ")

            val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return null)
            val token = md5.substringAfterLast("/")
            val randomString = createHashTable()
            val expiry = System.currentTimeMillis()

            // استخدام نفس الهوية العشوائية لجلب رابط الفيديو النهائي
            val videoUrlStart = client.newCall(
                GET(
                    md5,
                    Headers.headersOf("referer", newUrl, "User-Agent", selectedUA),
                ),
            ).execute().body.string()
            
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
            
            // تمرير الهوية العشوائية إلى المشغل لضمان عمل الرابط
            Video(videoUrl, newQuality, videoUrl, headers = doodHeaders(doodHost, selectedUA), subtitleTracks = externalSubs)
        }.getOrNull()
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let(::listOf) ?: emptyList()
    }

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun doodHeaders(host: String, ua: String) = Headers.Builder().apply {
        add("User-Agent", ua)
        add("Referer", "https://$host/")
    }.build()
}
