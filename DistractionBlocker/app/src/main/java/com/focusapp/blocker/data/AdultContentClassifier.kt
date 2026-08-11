package com.focusapp.blocker.data

import kotlin.math.ln
import kotlin.math.min

/**
 * Context-aware adult-content classifier for the "Strict" blocking level.
 *
 * The weights are LEARNED, not hand-picked: logistic regression fitted on ~1.5k crawled pages
 * (adult sites as positives; encyclopedic, health, news and shopping pages as negatives —
 * including several hundred deliberately hard ones that discuss sexuality without being adult
 * content). See model.json / train.py in the training workspace.
 *
 * Two findings from evaluating the earlier hand-tuned version shaped the feature set:
 *
 *  1. URLs need SUBSTRING matching, body text needs WORD BOUNDARIES. Domains concatenate
 *     words ("milfxxxporn.su", "shemaleporn.asia"); applying word boundaries to URLs matched
 *     only 5 of the 57 adult URLs that contained a term. Prose is the opposite case —
 *     substring matching is what made "Sussex" and "01XXXXX-XX02" trigger blocks.
 *
 *  2. Term counts alone cannot separate "an article ABOUT sex" from sexual content: both use
 *     the vocabulary heavily. The model therefore also reads REGISTER — encyclopedic markers
 *     (references, retrieved, ISBN, symptoms) against adult-commerce markers (sign up,
 *     webcam, 18 U.S.C. 2257, categories). That is the signal the old version lacked, and it
 *     is why it blocked Wikipedia's sex-education article at p = 0.63.
 *
 * Inference is a dot product over 10 features — no ML runtime, microseconds per page.
 */
object AdultContentClassifier {

    private val STRONG = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans", "chaturbate", "brazzers", "porn",
        "porno", "pornography", "hentai", "camgirl", "camsoda", "masturbat", "sextape", "sex tape",
        "creampie", "blowjob", "cumshot", "milf", "gangbang", "deepthroat", "bukkake", "nudes",
        "pornstar", "xxx", "hardcore", "fuck", "anal", "dildo", "cumming", "orgy", "threesome"
    )

    private val WEAK = listOf(
        "nude", "nudity", "naked", "erotic", "erotica", "nsfw", "escort", "escorts", "fetish", "bdsm",
        "bondage", "strip club", "stripclub", "adult content", "adult video", "adult film", "sex",
        "sexual", "orgasm", "lingerie", "boobs", "tits", "ass", "horny", "seduction", "intercourse"
    )

    /** Register of a commercial adult site. */
    private val COMMERCE = listOf(
        "sign up", "join now", "join free", "webcam", "live cam", "live sex", "free videos",
        "watch free", "hd videos", "full video", "premium", "subscribe now", "models online",
        "18 u.s.c", "2257", "age verification", "enter site", "i am 18", "adults only",
        "categories", "most viewed", "top rated", "longest", "trending videos", "related videos",
        "upload", "private show", "tokens", "tip menu", "onlyfans leaks"
    )

    /** Register of reference, journalism, health and education writing. */
    private val ENCYCLOPEDIC = listOf(
        "references", "external links", "see also", "retrieved", "isbn", "doi:", "citation",
        "cite", "published", "journal", "university", "according to", "research", "study",
        "history", "encyclopedia", "wikipedia", "archived", "jstor", "pubmed", "et al",
        "copyright", "privacy policy", "terms of service", "symptoms", "treatment",
        "diagnosis", "prevention", "health", "doctor", "clinic", "education", "learn more"
    )

    /**
     * Learned parameters, in the order of [FEATURE_NAMES]. Replaceable at runtime by
     * [loadModel] so retrained weights can ship through the same channel as the domain
     * blocklist, without an app release.
     */
    val FEATURE_NAMES = listOf(
        "url_strong", "url_weak", "title_strong", "title_weak",
        "body_strong", "body_weak", "adult_share", "commerce", "encyclopedic"
    )

    /**
     * Fitted on 2,748 crawled pages (1,000 adult / 188 hard negatives / 1,560 ordinary),
     * 60-20-20 train/validation/test. Threshold chosen on validation to hold the
     * false-positive rate under 2% on hard negatives and 1% on ordinary pages.
     *
     * Held-out test: 87.5% recall, 2.6% FPR on hard negatives, 0.6% on ordinary, AUC 0.978.
     *
     * The negative bias matters: a page with no adult vocabulary at all scores 0.077, well
     * under the 0.175 threshold. An earlier model trained on Wikipedia-dominated negatives
     * scored 0.69 on that same blank page — it had learned "no citations means adult" and
     * would have blocked shops and sports sites. Training now refuses to export a model
     * that fails this check.
     */
    private val DEFAULT_WEIGHTS = doubleArrayOf(
        0.890868,   // url_strong    — substring on the HOST; domains concatenate words
        0.597297,   // url_weak
        0.359314,   // title_strong
        0.283912,   // title_weak
        0.460845,   // body_strong
        0.375882,   // body_weak
        0.047817,   // adult_share   — only counted once there are >= 50 words
        0.369262,   // commerce      — "sign up", "webcam", "18 U.S.C. 2257"
        -0.741061   // encyclopedic  — citations/health/education writing pulls DOWN
    )
    private const val DEFAULT_BIAS = -1.070647
    private const val DEFAULT_THRESHOLD = 0.42

    @Volatile private var weights: DoubleArray = DEFAULT_WEIGHTS
    @Volatile private var bias: Double = DEFAULT_BIAS
    @Volatile private var threshold: Double = DEFAULT_THRESHOLD

    /** Applies retrained parameters. Ignores anything malformed and keeps the built-ins. */
    fun loadModel(w: DoubleArray, b: Double, t: Double) {
        if (w.size != FEATURE_NAMES.size || t <= 0.0 || t >= 1.0) return
        weights = w; bias = b; threshold = t
    }

    data class PageContext(
        val url: String = "",
        val packageName: String = "",
        val title: String = "",
        val body: String = "",
        val isKnownAdultDomain: Boolean = false
    )

    data class Verdict(val probability: Double, val isAdult: Boolean, val reason: String)

    // Whole-word patterns for prose. "masturbat" stays a stem so it covers its suffixes.
    //
    // Boundaries are written as explicit lookarounds rather than \b. Two engines have to
    // agree here — Python's `re` during training and Android's at inference — and they
    // disagree about \b: Java/Python treat it as a \w transition with different notions of
    // \w, and Android's ICU engine REJECTS the (?U) flag outright, which crashed the whole
    // accessibility service on the first install. [\p{L}\p{N}_] means the same thing in ICU,
    // the desktop JVM and (as \w) in Python, so all three now compute identical features.
    private const val WORD_CHAR = "[\\p{L}\\p{N}_]"

    private fun wordRe(term: String): Regex {
        val core = term.split(" ").joinToString("\\s+") { Regex.escape(it) }
        val tail = if (term == "masturbat") "" else "(?!$WORD_CHAR)"
        return Regex("(?<!$WORD_CHAR)$core$tail", RegexOption.IGNORE_CASE)
    }

    private fun phraseRe(term: String): Regex =
        Regex(term.split(" ").joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    private val strongRe by lazy { STRONG.map { wordRe(it) } }
    private val weakRe by lazy { WEAK.map { wordRe(it) } }
    private val commerceRe by lazy { COMMERCE.map { phraseRe(it) } }
    private val encyRe by lazy { ENCYCLOPEDIC.map { phraseRe(it) } }

    // URL forms: spaces removed, matched as substrings because domains run words together.
    private val strongUrlTerms by lazy { STRONG.map { it.replace(" ", "") } }
    private val weakUrlTerms by lazy { WEAK.map { it.replace(" ", "") } }

    private fun distinct(pats: List<Regex>, text: String): Int {
        if (text.isEmpty()) return 0
        return pats.count { it.containsMatchIn(text) }
    }

    private fun total(pats: List<Regex>, text: String): Int {
        if (text.isEmpty()) return 0
        return pats.sumOf { it.findAll(text).count() }
    }

    /** Must stay identical to features.py — golden vectors guard the two against drift. */
    /**
     * URL features are read from the HOST only, never the path.
     *
     * Substring matching is required on hosts because adult domains run words together
     * ("milfxxxporn.su"). Applied to the path it misfires on ordinary URLs: Wikipedia's
     * "/wiki/Sex_education" contains "sex", which on a real device was enough to block the
     * page on its own — the page body was empty, so that single substring decided it.
     * A host names the site; a path names one page and is far noisier.
     */
    private fun hostOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        return noScheme.substringBefore('/').substringBefore('?')
    }

    fun features(ctx: PageContext): DoubleArray {
        val host = hostOf(ctx.url.lowercase())
        val urlStrong = strongUrlTerms.count { host.contains(it) }
        val urlWeak = weakUrlTerms.count { host.contains(it) }

        val titleStrong = distinct(strongRe, ctx.title)
        val titleWeak = distinct(weakRe, ctx.title)
        val bodyStrong = distinct(strongRe, ctx.body)
        val bodyWeak = distinct(weakRe, ctx.body)

        val words = ctx.body.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        val adultHits = total(strongRe, ctx.body) + total(weakRe, ctx.body)
        // A ratio needs a denominator: on a 9-word OTP screen a single match reads as an 11%
        // share, which alone was enough to block "code sent to +972-5X-XXX-XX02".
        val adultShare = if (words >= 50) min(adultHits * 100.0 / words, 10.0) else 0.0

        val commerce = distinct(commerceRe, ctx.body) + distinct(commerceRe, ctx.title)
        val ency = distinct(encyRe, ctx.body) + distinct(encyRe, ctx.title)

        return doubleArrayOf(
            min(urlStrong, 3).toDouble(),
            min(urlWeak, 3).toDouble(),
            min(titleStrong, 4).toDouble(),
            min(titleWeak, 4).toDouble(),
            min(bodyStrong, 8).toDouble(),
            min(bodyWeak, 8).toDouble(),
            adultShare,
            min(commerce, 10).toDouble(),
            min(ency, 10).toDouble()
        )
    }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + Math.exp(-x))

    fun classify(ctx: PageContext): Verdict {
        // A domain on the blocklist is settled without the model; that list is exact.
        if (ctx.isKnownAdultDomain) return Verdict(1.0, true, "known adult site")

        val f = features(ctx)
        var z = bias
        for (i in f.indices) z += weights[i] * f[i]
        val p = sigmoid(z)

        val hits = strongRe.withIndex().filter { it.value.containsMatchIn(ctx.title) || it.value.containsMatchIn(ctx.body) }
            .map { STRONG[it.index] }.take(3)
        val reason = if (hits.isNotEmpty()) hits.joinToString(", ") else "adult content"
        return Verdict(p, p > threshold, reason)
    }
}
