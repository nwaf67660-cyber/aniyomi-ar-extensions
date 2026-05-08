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
        val originalRequest = chain.request()
        val originalResponse = chain.proceed(chain.request())

        // Check if the response indicates a Cloudflare challenge (403 or 503)
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            return originalResponse
        }

        return try {
            originalResponse.close()
            val request = resolveWithWebView(originalRequest, client)
            chain.proceed(request)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    class CloudflareJSI(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() = latch.countDown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveWithWebView(request: Request, client: OkHttpClient): Request {
        val latch = CountDownLatch(1)
        val jsInterface = CloudflareJSI(latch)
        var webView: WebView? = null

        val origRequestUrl = request.url.toString()
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                // Randomly select a User-Agent for each request to avoid fingerprinting
                userAgentString = USER_AGENTS.random()
            }

            webview.addJavascriptInterface(jsInterface, "CloudflareJSI")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Inject the auto-click script when the page finishes loading
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        // Wait for the challenge to be solved or timeout after 45 seconds
        latch.await(45, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val cookies = CookieManager.getInstance()
            ?.getCookie(origRequestUrl)
            ?.split(";")
            ?.mapNotNull { Cookie.parse(request.url, it) }
            ?: emptyList<Cookie>()

        cookies.forEach {
            client.cookieJar.saveFromResponse(
                url = HttpUrl.Builder().scheme("https").host(it.domain).build(),
                cookies = listOf(it),
            )
        }

        return createRequestWithCookies(request, cookies)
    }

    private fun createRequestWithCookies(request: Request, cookies: List<Cookie>): Request {
        val convertedForThisRequest = cookies.filter { it.matches(request.url) }
        val existingCookies = Cookie.parseAll(request.url, request.headers)
        val filteredExisting = existingCookies.filter { existing ->
            convertedForThisRequest.none { converted -> converted.name == existing.name }
        }

        val newCookies = filteredExisting + convertedForThisRequest
        return request.newBuilder()
            .header("Cookie", newCookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )

        private val CHECK_SCRIPT by lazy {
            """
            (function() {
                const performCheck = (context) => {
                    // Check for common Cloudflare challenge elements
                    if (context.querySelector("#challenge-form, #challenge-stage, .ray_id")) {
                        // Try clicking the simple challenge button if it exists
                        const challengeBtn = context.querySelector("#challenge-stage input[type='button'], #security-button");
                        if (challengeBtn) challengeBtn.click();

                        // Search and click checkboxes inside Iframes (Turnstile/hCaptcha)
                        context.querySelectorAll("iframe").forEach(iframe => {
                            try {
                                const iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                                if (iframeDoc) {
                                    const checkbox = iframeDoc.querySelector("input[type='checkbox']");
                                    if (checkbox) checkbox.click();
                                }
                            } catch (e) {
                                // Ignore Cross-Origin access errors
                            }
                        });
                        return true;
                    }
                    return false;
                };

                // Run check every 2 seconds
                const checkTimer = setInterval(() => {
                    if (!performCheck(document)) {
                        // If no more challenge elements found, leave the WebView
                        CloudflareJSI.leave();
                        clearInterval(checkTimer);
                    }
                }, 2000);
            })();
            """.trimIndent()
        }
    }
}
