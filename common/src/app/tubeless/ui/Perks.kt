package app.tubeless.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubeless.data.JsonFile
import app.tubeless.engine.Sys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * The player's Premium perks (S5): stable volume, voice boost, auto speed, skip silence, jump ahead,
 * SponsorBlock's per-channel whitelist and the speed held down. Their choices live in a file of their
 * own (perks.json in the data folder), so the shared settings file and its owner stay untouched.
 */
@Serializable
data class PerkPrefs(
    /** YouTube's "Stable volume": loud and quiet parts evened out (on by default, as on YouTube) */
    val stableVolume: Boolean = true,
    /** "Voice boost": speech lifted over music and noise */
    val voiceBoost: Boolean = false,
    /** speed follows how fast people speak (from the captions' words per minute) */
    val autoSpeed: Boolean = false,
    /** Android: silent stretches are skipped */
    val skipSilence: Boolean = false,
    /** after a double tap or → forward: "Jump ahead" to where most viewers skip to */
    val jumpAhead: Boolean = true,
    /** the speed a press held on the video plays at */
    val holdSpeed: Float = 2f,
    /** SponsorBlock: channels whose segments are never skipped by themselves (id → name) */
    val sponsorAllow: Map<String, String> = emptyMap(),
)

/** what a platform's player can do to the sound; null where it can't (rows for it are hidden) */
interface AudioFx {
    val canStable: Boolean get() = true
    val canVoice: Boolean get() = true
    val canSkipSilence: Boolean get() = false
    fun apply(stable: Boolean, voice: Boolean)
    fun setSkipSilence(on: Boolean) {}
}

object Perks {
    private var file: JsonFile<PerkPrefs>? = null
    /** the platform's sound effects (set by the desktop's MpvFx, AndroidPlayer, IosPlayer) */
    var fx: AudioFx? = null
    /** desktop: the picture-in-picture window is open (the page shows a note where the video was) */
    var pipOpen by mutableStateOf(false)
    /** the keyboard shortcuts sheet (Shift+/) */
    var shortcuts by mutableStateOf(false)
    /** a press is held on the video: auto speed leaves the speed alone */
    var holding = false
    private var app: AppState? = null

    var prefs by mutableStateOf(PerkPrefs())
        private set

    fun init(a: AppState) {
        if (app != null) return
        app = a
        val f = JsonFile(a.platform.dataDir / "perks.json", PerkPrefs.serializer(), PerkPrefs(), a.scope)
        file = f
        prefs = f.value
        PerkSettings.register()
    }

    fun update(change: (PerkPrefs) -> PerkPrefs) {
        val n = change(prefs)
        prefs = n
        file?.update { n }
        file?.flushNow()
        applyFx()
    }

    /** the sound effects as chosen (none for music, whose mastering stays as it is) */
    fun applyFx() {
        val p = prefs
        val music = app?.playback?.let { it.music && !it.withVideo } == true
        fx?.apply(p.stableVolume && !music, p.voiceBoost && !music)
        fx?.setSkipSilence(p.skipSilence)
    }

    fun sponsorAllowed(channelId: String?) = !channelId.isNullOrEmpty() && channelId in prefs.sponsorAllow
}

/**
 * The E2E event log of scripts/e2e-m3.sh: with TUBELESS_E2E_LOG=<file> set (the platform passes it in
 * as [path]), one JSON object per line, e.g. {"event":"speed","x":3.0}. Off (and free) otherwise.
 */
object E2E {
    var path: String? = null
    /** the iOS simulator check: Shorts moves on by itself this often (ms); 0 = never */
    var shortsSwipeMs = 0L
    private val lock = app.tubeless.engine.SysLock()

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val p = path?.takeIf { it.isNotEmpty() } ?: return
        val o = buildJsonObject {
            put("event", JsonPrimitive(event))
            put("t", JsonPrimitive(Sys.nowMs()))
            for ((k, v) in fields) put(k, when (v) {
                null -> kotlinx.serialization.json.JsonNull
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            })
        }
        runCatching {
            lock.run { Sys.fs.appendingSink(p.toPath()).buffer().use { it.writeUtf8(o.toString()).writeUtf8("\n") } }
        }
    }
}
