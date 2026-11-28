package app.tubeless.ios

import app.tubeless.ui.Platform
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.useContents

/** What iOS supplies: folders, the share sheet, Files for import/export, the screen brightness. */
class IosPlatform : Platform {
    override val name = "ios"
    override val dataDir: Path = (folder(NSApplicationSupportDirectory) / "tubeless").also { now ->
        // installs from before the rename: the old folder moves over once
        val old = folder(NSApplicationSupportDirectory) / app.tubeless.Legacy.DATA_DIR
        val fs = okio.FileSystem.SYSTEM
        if (!fs.exists(now) && fs.metadataOrNull(old)?.isDirectory == true) runCatching { fs.atomicMove(old, now) }
    }
    override val cacheDir: Path = folder(NSCachesDirectory)
    override val touch = true

    init {
        // what nothing else plays: SABR from a hidden page on www.youtube.com, its formats kept in Caches while they play;
        // a file named "sabr-only" in Files → On My iPad → Tubeless plays everything that way (for testing)
        val sabr = cacheDir / "sabr"
        runCatching { okio.FileSystem.SYSTEM.deleteRecursively(sabr) }
        app.tubeless.engine.Sabr.dir = sabr.toString()
        app.tubeless.engine.Sabr.host = WebSabr()
        // the TV client's locked links (videos made for kids, some labels' music): yt-dlp's solver in JavaScriptCore
        app.tubeless.engine.Cipher.solver = app.tubeless.engine.JscSolver()
        app.tubeless.engine.Sabr.only = okio.FileSystem.SYSTEM.exists(folder(platform.Foundation.NSDocumentDirectory) / "sabr-only")
    }
    /** Roboto (YouTube's typeface), from the app bundle (one variable file, its weight axis set for each weight); other scripts fall back to the system's fonts */
    override val font by lazy {
        val path = platform.Foundation.NSBundle.mainBundle.pathForResource("RobotoVariable", "ttf") ?: return@lazy null
        val d = NSData.create(contentsOfFile = path) ?: return@lazy null
        val data = ByteArray(d.length.toInt())
        if (data.isEmpty()) return@lazy null
        data.usePinned { platform.posix.memcpy(it.addressOf(0), d.bytes, d.length) }
        runCatching {
            androidx.compose.ui.text.font.FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
                androidx.compose.ui.text.platform.Font("Roboto-$w", data, androidx.compose.ui.text.font.FontWeight(w),
                    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
            })
        }.getOrNull()
    }
    override val player = IosPlayer()
    override val canPip: Boolean get() = platform.AVKit.AVPictureInPictureController.isPictureInPictureSupported()
    override fun startPip() = player.startPip()
    /** AirPlay instead of Google Cast: the system's list of TVs */
    override val airplay: (@androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit)? = { m -> AirPlayButton(m, player) }

    /** the window's view controller, to present sheets from; set by MainViewController */
    var root: UIViewController? = null

    // the document picker holds its delegate weakly: keep it alive while the picker is open
    private var picker: PickerDelegate? = null
    private var pickerMany: ManyPickerDelegate? = null

    private fun folder(kind: ULong): Path =
        ((NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String) ?: NSTemporaryDirectory()).toPath()

    /** the controller on top (a sheet may already be showing) */
    private fun top(): UIViewController? {
        var vc = root ?: UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (vc.presentedViewController != null) vc = vc.presentedViewController!!
        return vc
    }

    /** iPad sheets are popovers and need a place to point at: the middle of the screen */
    private fun present(vc: UIViewController) {
        val host = top() ?: return
        vc.popoverPresentationController?.let { p ->
            p.sourceView = host.view
            host.view.bounds.useContents { p.sourceRect = CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
            p.permittedArrowDirections = 0uL
        }
        host.presentViewController(vc, animated = true, completion = null)
    }

    // comment translation: the Swift side's (App.swift), iOS 18+
    override val canTranslate get() = CommentTranslation.translator != null
    override suspend fun translate(text: String, target: String) = CommentTranslation.translate(text, target)
    override fun textLanguage(text: String): String? {
        val r = platform.NaturalLanguage.NLLanguageRecognizer()
        r.processString(text)
        val best = r.languageHypothesesWithMaximum(1u).entries.firstOrNull() ?: return null
        // only a sure guess hides the link (short comments are often guessed wrong)
        return (best.key as? String)?.takeIf { ((best.value as? Number)?.toDouble() ?: 0.0) >= 0.85 }
    }

    override fun share(title: String, url: String): Boolean {
        present(UIActivityViewController(activityItems = listOf(url), applicationActivities = null))
        return true
    }

    override fun openExternal(url: String) {
        val u = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(u, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /** writes the file to a temporary place, then Files asks where it goes */
    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name.substringAfterLast('/'))
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (!data.writeToURL(url, atomically = true)) { done(false); return }
        val vc = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        picker = PickerDelegate { picked -> picker = null; done(picked != null) }
        vc.delegate = picker
        present(vc)
    }

    override fun openTextFile(done: (String?) -> Unit) {
        val vc = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        picker = PickerDelegate { url ->
            picker = null
            done(url?.let { NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) })
        }
        vc.delegate = picker
        present(vc)
    }

    override fun openTextFiles(done: (List<Pair<String, String>>) -> Unit) {
        val vc = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        vc.allowsMultipleSelection = true
        pickerMany = ManyPickerDelegate { urls ->
            pickerMany = null
            done(urls.mapNotNull { u ->
                NSString.stringWithContentsOfURL(u, encoding = NSUTF8StringEncoding, error = null)?.let { (u.lastPathComponent ?: "") to it }
            })
        }
        vc.delegate = pickerMany
        present(vc)
    }

    /** set by the Swift side (ios/app/App.swift): hides the status bar (clock, Wi-Fi, battery) and the home indicator */
    var hideBars: (Boolean) -> Unit = {}

    // the app draws full screen itself; iOS keeps the clock and battery over the video unless told
    override fun setFullscreen(on: Boolean) = hideBars(on)

    /** mobile data or Low Data Mode (the Network framework's path): "Quality on mobile data" applies, as on Android */
    private var expensive = false
    private val monitor = platform.Network.nw_path_monitor_create().also { m ->
        platform.Network.nw_path_monitor_set_update_handler(m) { path ->
            expensive = platform.Network.nw_path_is_expensive(path) || platform.Network.nw_path_is_constrained(path)
        }
        platform.Network.nw_path_monitor_set_queue(m, platform.darwin.dispatch_get_main_queue())
        platform.Network.nw_path_monitor_start(m)
    }
    override val knowsMetered = true
    /** voice search: Apple's Speech framework (IosSpeech) */
    override val speech: app.tubeless.ui.Speech = IosSpeech
    override fun metered() = expensive

    /**
     * The clock and battery follow the window's appearance, so the window is told dark (Shorts), light,
     * or nothing (the system's). The app's theme reads its own setting, so only "follow the system"
     * would turn over with it, and that one is passed as null.
     */
    override fun statusBarDark(on: Boolean?) {
        root?.view?.window?.overrideUserInterfaceStyle = when (on) {
            true -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
            false -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleLight
            null -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        }
    }

    /** the Taptic Engine (Haptics.kt) */
    override fun vibrate(type: androidx.compose.ui.hapticfeedback.HapticFeedbackType) = Haptics.play(type)

    override fun brightness(): Float = UIScreen.mainScreen.brightness.toFloat()
    override fun setBrightness(v: Float) { UIScreen.mainScreen.brightness = v.toDouble().coerceIn(0.0, 1.0) }
}

private class ManyPickerDelegate(val picked: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        picked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        picked(emptyList())
    }
}

private class PickerDelegate(val picked: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        picked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        picked(null)
    }
}
