package app.tubeless.engine

/**
 * The sound a player plays: the track asked for (a dub's language) or else the original, and in it the best
 * format: AAC 141 (256 kbps, YouTube Music Premium) where it is offered, else Opus 251 (~130-160 kbps), else
 * AAC 140 (128 kbps), else whatever is best. The low ones (139 HE-AAC 48 kbps, 249/250 Opus 50-70 kbps, 599/600) are taken only when a
 * track has nothing else, never because they come first in YouTube's list.
 */
object AudioPick {
    /** the formats of the track in language [lang] (null: the original track) */
    fun track(audio: List<Format>, lang: String? = null): List<Format> =
        lang?.let { l -> audio.filter { it.audioLang == l } }?.ifEmpty { null }
            ?: audio.filter { it.audioContent == "original" }.ifEmpty { null }
            ?: audio.filter { it.audioDefault }.ifEmpty { audio }

    /** the best format of [track]'s formats ([mp4Only]: AAC in mp4 only, for players that play nothing else) */
    fun best(audio: List<Format>, lang: String? = null, mp4Only: Boolean = false): Format? {
        val main = track(audio.filter { !it.drc }.ifEmpty { audio }, lang).let { l -> if (mp4Only) l.filter { it.codecFamily == "aac" } else l }
            // stereo first: 5.1 (258, 327, 338) is a surround mix a computer or a phone folds back down anyway
            .let { l -> l.filter { it.channels <= 2 }.ifEmpty { l } }
        // Opus sounds as good as AAC at about 1.5 times its bitrate: 251 wins over 140, 141 (256 kbps) over 251
        return main.filter { it.codecFamily == "opus" || it.codecFamily == "aac" }.maxByOrNull { score(it) }
            ?: main.maxByOrNull { it.bitrate }
    }

    /** the original language's track ("en-US"); null when YouTube doesn't say which one is the original */
    fun originalLang(audio: List<Format>): String? =
        (audio.firstOrNull { it.audioContent == "original" } ?: audio.firstOrNull { it.audioDefault && it.audioLang != null })?.audioLang

    /**
     * The language a video starts in, as YouTube does it: the original, unless the person speaks none of it and
     * the video has a dub in one of their languages ([mine]: the app's language, then the device's list, in
     * order); then the dub in the first of those that has one. [dubs] off (Settings → "Always original"): the
     * original. A dub in a language the person doesn't have is never picked (AVPlayer, left to itself, took
     * the first track of YouTube's list, Arabic, for anybody). null: one track only, or no known original.
     */
    fun preferred(audio: List<Format>, mine: List<String>, dubs: Boolean = true): String? {
        val langs = audio.mapNotNull { it.audioLang }.distinct()
        if (langs.size < 2) return null
        val original = originalLang(audio) ?: return null
        if (!dubs) return original
        fun base(l: String) = l.substringBefore('-').lowercase()
        val wanted = mine.map(::base).filter { it.isNotEmpty() }.distinct()
        if (base(original) in wanted) return original
        for (w in wanted) langs.firstOrNull { it != original && base(it) == w }?.let { return it }
        return original
    }

    private fun score(f: Format): Double = if (f.codecFamily == "opus") f.bitrate * 1.5 else f.bitrate.toDouble()
}
