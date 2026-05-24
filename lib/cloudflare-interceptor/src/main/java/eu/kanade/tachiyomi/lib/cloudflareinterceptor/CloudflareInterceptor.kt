package eu.kanade.tachiyomi.lib.cloudflareinterceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(private val client: OkHttpClient) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        // FIX 1: استخدام originalRequest بدل إعادة بناء الطلب من chain.request()
        val originalRequest = chain.request()
        val originalResponse = chain.proceed(originalRequest)

        // إذا لم يكن Cloudflare هو السبب، أعد الرد كما هو
        if (!isCloudflareChallenge(originalResponse)) {
            return originalResponse
        }

        return try {
            // FIX 2: إغلاق الرد الأصلي قبل المتابعة لتجنب تسريب الموارد
            originalResponse.close()
            val resolvedRequest = resolveWithWebView(originalRequest)
            chain.proceed(resolvedRequest)
        } catch (e: Exception) {
            // OkHttp يدعم فقط IOExceptions في enqueue، لذا نغلّف الاستثناء
            throw IOException("Cloudflare challenge failed for ${originalRequest.url}", e)
        }
    }

    /** التحقق مما إذا كانت الاستجابة هي تحدي Cloudflare */
    private fun isCloudflareChallenge(response: Response): Boolean {
        return response.code in ERROR_CODES &&
            response.header("Server") in SERVER_CHECK
    }

    class CloudflareJSI(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() = latch.countDown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveWithWebView(request: Request): Request {
        val latch = CountDownLatch(1)
        val jsInterface = CloudflareJSI(latch)

        // FIX 3: استخدام @Volatile لضمان رؤية آمنة بين الخيوط
        @Volatile var webView: WebView? = null

        val origRequestUrl = request.url.toString()
        val headers = request.headers
            .toMultimap()
            .mapValues { it.value.firstOrNull().orEmpty() }
            .toMutableMap()

        handler.post {
            val wv = WebView(context).also { webView = it }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                userAgentString = request.header("User-Agent") ?: DEFAULT_USER_AGENT
            }
            wv.addJavascriptInterface(jsInterface, "CloudflareJSI")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }
            }
            wv.loadUrl(origRequestUrl, headers)
        }

        // الانتظار حتى يتم حل التحدي أو انتهاء المهلة
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // FIX 4: تنظيف WebView دائمًا حتى في حالة الاستثناء
        handler.post {
            webView?.run {
                stopLoading()
                destroy()
            }
            webView = null
        }

        val cookies = parseCookiesFromWebView(request)

        // FIX 5: حفظ جميع الكوكيز بـ استدعاء واحد فقط بدل حلقة
        if (cookies.isNotEmpty()) {
            val cookiesByDomain = cookies.groupBy { it.domain }
            cookiesByDomain.forEach { (domain, domainCookies) ->
                client.cookieJar.saveFromResponse(
                    url = HttpUrl.Builder()
                        .scheme("https")
                        .host(domain)
                        .build(),
                    cookies = domainCookies,
                )
            }
        }

        return createRequestWithCookies(request, cookies)
    }

    /** استخراج الكوكيز من WebView بشكل آمن */
    private fun parseCookiesFromWebView(request: Request): List<Cookie> {
        return CookieManager.getInstance()
            ?.getCookie(request.url.toString())
            ?.split(";")
            ?.mapNotNull { Cookie.parse(request.url, it.trim()) }
            ?: emptyList()
    }

    private fun createRequestWithCookies(request: Request, cookies: List<Cookie>): Request {
        val matchingCookies = cookies.filter { it.matches(request.url) }

        // الاحتفاظ بالكوكيز الموجودة التي لا تتعارض مع الكوكيز الجديدة
        val existingCookies = Cookie.parseAll(request.url, request.headers)
        val filteredExisting = existingCookies.filter { existing ->
            matchingCookies.none { new -> new.name == existing.name }
        }

        val mergedCookies = filteredExisting + matchingCookies
        return request.newBuilder()
            .header("Cookie", mergedCookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        private const val TIMEOUT_SECONDS = 30L

        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"

        // FIX 6: CHECK_SCRIPT ثابت حقيقي — لا حاجة لـ lazy لأنه String بسيط
        private const val CHECK_SCRIPT = """
            setInterval(() => {
                if (document.querySelector("#challenge-form") != null) {
                    const simpleChallenge = document.querySelector(
                        "#challenge-stage > div > input[type='button']"
                    );
                    if (simpleChallenge != null) simpleChallenge.click();

                    const turnstile = document.querySelector("div.hcaptcha-box > iframe");
                    if (turnstile != null) {
                        const button = turnstile.contentWindow.document.querySelector(
                            "input[type='checkbox']"
                        );
                        if (button != null) button.click();
                    }
                } else {
                    CloudflareJSI.leave();
                }
            }, 2500);
        """
    }
}
