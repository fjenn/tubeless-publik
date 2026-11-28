package app.tubeless.engine

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * The one HTTP client of the app on iOS (Apple's URLSession, through Ktor). The same two calls as
 * the OkHttp one in common/jvm: to YouTube from this device, or to the connected Tubeless server.
 */
object Net {
    /**
     * A test switch (TUBELESS_NO_NET=1 in the launch environment, the simulator's airplane mode): every web request
     * of the app fails as it does with no connection (Net's calls at once, logged as net_blocked; images through a
     * dead proxy). Never set on a phone.
     */
    val dead: Boolean = platform.Foundation.NSProcessInfo.processInfo.environment["TUBELESS_NO_NET"] == "1"

    private fun refuse(url: String): Nothing {
        app.tubeless.ui.E2E.log("net_blocked", "url" to url.substringBefore('?').take(160))
        throw okio.IOException("The Internet connection appears to be offline. (test)")
    }

    val client = HttpClient(Darwin) {
        expectSuccess = false
        // no shared cookie store: URLSession would otherwise keep the account's Set-Cookie and send it on the
        // anonymous stream calls too. The account's cookies are sent by hand (Accounts, S3), like OkHttp on the JVM.
        engine {
            configureSession {
                setHTTPShouldSetCookies(false)
                setHTTPCookieAcceptPolicy(platform.Foundation.NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever)
                setHTTPCookieStorage(null)
                if (dead) setConnectionProxyDictionary(mapOf<Any?, Any?>("HTTPEnable" to 1, "HTTPProxy" to "127.0.0.1", "HTTPPort" to 9,
                    "HTTPSEnable" to 1, "HTTPSProxy" to "127.0.0.1", "HTTPSPort" to 9))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val text = local(url)?.second?.decodeToString() ?: call(HttpMethod.Get, url, null, headers)
        // subtitles arrive cleaned (Vtt), as on the other platforms
        return if (Vtt.isCaption(url)) Vtt.clean(text) else text
    }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        call(HttpMethod.Post, url, body, headers)

    /** a GET whose answer is bytes (a range of a video format): its status and body; only a failed connection throws */
    suspend fun bytes(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        local(url)?.let { return it }
        if (dead) refuse(url)
        val r: HttpResponse = try {
            client.request(url) { this.method = HttpMethod.Get; headers.forEach { (k, v) -> header(k, v) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw okio.IOException(e.message ?: "network error")
        }
        return r.status.value to r.body<ByteArray>()
    }

    /**
     * A saved file (a downloaded video's subtitles are file:// addresses): read from disk. Ktor's URLSession engine
     * must never get one: it takes every answer for an HTTP one and the app crashes (offline playback, build 2).
     */
    private suspend fun local(url: String): Pair<Int, ByteArray>? {
        if (!url.startsWith("file:", ignoreCase = true)) return null
        val path = platform.Foundation.NSURL.URLWithString(url)?.path ?: throw okio.IOException("bad file address")
        return kotlinx.coroutines.withContext(Sys.IO) {
            try { 200 to Sys.fs.read(okio.Path.Companion.run { path.toPath() }) { readByteArray() } }
            catch (e: Exception) { throw okio.IOException(e.message ?: "file not readable") }
        }
    }

    private suspend fun call(method: HttpMethod, url: String, body: String?, headers: Map<String, String>): String {
        if (!url.startsWith("http", ignoreCase = true)) throw okio.IOException("not a web address")
        if (dead) refuse(url)
        val r: HttpResponse = try {
            client.request(url) {
                this.method = method
                ServerVideo.authFor(url)?.takeIf { headers.keys.none { it.equals("Authorization", true) } }?.let { header("Authorization", it) }
                headers.filter { it.value.isNotEmpty() && it.key != InnerTube.SESSION_MARK }.forEach { (k, v) -> header(k, v) }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the shared code catches okio's IOException for "no connection"; Ktor throws its own
            throw okio.IOException(e.message ?: "network error")
        }
        // the account's rotated cookies, from signed youtube.com answers only (Accounts, S3)
        if (InnerTube.SESSION_MARK in headers || headers.entries.any { it.key.equals("Authorization", true) && it.value.startsWith("SAPISID") }) {
            val set = r.headers.getAll("Set-Cookie").orEmpty().flatMap { CookieJar.splitJoined(it) }
            if (set.isNotEmpty()) Accounts.capture(url, set)
        }
        val text = try { r.bodyAsText() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw okio.IOException(e.message ?: "network error") }
        if (r.status.value !in 200..299) throw HttpError(r.status.value, "HTTP ${r.status.value}")
        return text
    }
}
