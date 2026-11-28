package app.tubeless.engine

import coil3.ComponentRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The few things the shared code needs from the system, the browser's way. Twins: common/jvm, common/ios. */
object Sys {
    fun nowMs(): Long = jsNow().toLong()

    /** the app's files: in memory, every write kept in IndexedDB and read back at start (web/WebFs.kt) */
    val fs: okio.FileSystem get() = app.tubeless.web.WebFs.fs

    /** the browser has one thread: "IO" is the default dispatcher, the network is async anyway */
    val IO: CoroutineDispatcher = Dispatchers.Default

    fun isoSeconds(s: String): Long? = jsParseDate(s).takeIf { !it.isNaN() }?.let { (it / 1000).toLong() }
    fun today(): String = jsToday()
    fun hour(): Int = jsHour()
    fun shortTime(ms: Long): String = jsShortTime(ms.toDouble())
    fun countryName(code: String, lang: String): String = jsDisplayName(lang, "region", code).ifEmpty { code }
    fun languageName(code: String, lang: String): String = jsDisplayName(lang, "language", code).ifEmpty { code }
    fun deviceLanguage(): String = jsNavigatorLanguage().substringBefore('-')
    /** the browser's languages, the first first */
    fun deviceLanguages(): List<String> = jsNavigatorLanguages().split(',').map { it.substringBefore('-') }.filter { it.isNotEmpty() }.distinct()
    fun deviceRegion(): String = jsNavigatorLanguage().substringAfter('-', "")

    /** one thread: a plain map is safe */
    fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()

    fun osName(): String = "Web"
    fun osArch(): String = "wasm"

    /** pictures: one plain fetch each (Coil's streaming Ktor fetcher trips over Ktor's body replay on wasm) */
    fun imageNetwork(b: ComponentRegistry.Builder) {
        b.add(ImageFetcher.Factory())
    }
}

/** one thread: nothing to lock */
class SysLock {
    fun <T> run(block: () -> T): T = block()
}

private fun jsNow(): Double = js("Date.now()")
private fun jsParseDate(s: String): Double = js("Date.parse(s)")
private fun jsToday(): String = js("new Date().toISOString().slice(0, 10)")
private fun jsHour(): Int = js("new Date().getHours()")
private fun jsShortTime(ms: Double): String = js("new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })")
private fun jsDisplayName(lang: String, type: String, code: String): String =
    js("(function(){ try { return new Intl.DisplayNames([lang], { type: type }).of(code) || '' } catch (e) { return '' } })()")
private fun jsNavigatorLanguage(): String = js("navigator.language || 'en-US'")
private fun jsNavigatorLanguages(): String = js("(navigator.languages || [navigator.language || 'en']).join(',')")

/** one thread: @Volatile means nothing in the browser */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Volatile

private class ImageFetcher(private val url: String, private val options: coil3.request.Options) : coil3.fetch.Fetcher {
    override suspend fun fetch(): coil3.fetch.FetchResult {
        val (code, bytes) = Net.bytes(url, emptyMap())
        if (code !in 200..299) throw okio.IOException("HTTP $code")
        val buf = okio.Buffer().write(bytes)
        return coil3.fetch.SourceFetchResult(coil3.decode.ImageSource(buf, options.fileSystem), null, coil3.decode.DataSource.NETWORK)
    }

    class Factory : coil3.fetch.Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: coil3.request.Options, imageLoader: coil3.ImageLoader): coil3.fetch.Fetcher? =
            if (data.scheme == "http" || data.scheme == "https" || data.scheme == "opfs") ImageFetcher(data.toString(), options) else null
    }
}
