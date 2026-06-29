package com.focusapp.blocker.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AdultBlockList {

    private const val TAG = "AdultBlockList"
    private const val BLOCKLIST_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/porn/sinfonietta/hosts"

    val KEYWORDS: Set<String> = setOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans",
        "porn", "pornography", "porno",
        "xxx", "nsfw", "hentai",
        "nude", "nudity", "naked",
        "erotic", "erotica",
        "escort", "escorts",
        "fetish", "bdsm", "bondage",
        "camgirl", "cam girl",
        "masturbat",
        "sex tape", "sextape",
        "strip club", "stripclub",
        "adult content", "adult video", "adult film"
    )

    // ~150 most-trafficked adult sites used as offline fallback when download hasn't run yet
    val FALLBACK_DOMAINS: Set<String> = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "tube8.com", "spankbang.com", "porntrex.com", "eporner.com",
        "onlyfans.com", "fapello.com", "thothub.to", "leakedzone.com",
        "brazzers.com", "naughtyamerica.com", "realitykings.com", "teamskeet.com",
        "bangbros.com", "digitalplayground.com", "mofos.com", "vivid.com",
        "playboy.com", "penthouse.com", "hustler.com",
        "chaturbate.com", "myfreecams.com", "cam4.com", "stripchat.com",
        "bongacams.com", "livejasmin.com", "streamate.com", "camsoda.com",
        "adultfriendfinder.com", "fling.com",
        "nhentai.net", "hanime.tv", "hentaihaven.org",
        "rule34.xxx", "gelbooru.com", "e621.net",
        "pornmd.com", "perfectgirls.net", "beeg.com", "txxx.com", "vporn.com",
        "hclips.com", "porntube.com", "keezmovies.com",
        "fuq.com", "tubegalore.com", "tnaflix.com", "empflix.com", "porndig.com",
        "4tube.com", "porn.com", "sex.com", "adult.com",
        "slutload.com", "motherless.com", "imagefap.com",
        "8muses.com", "luscious.net", "literotica.com", "sexstories.com",
        "skipthegames.com", "listcrawler.com",
        "javdb.com", "javhd.com", "r18.com",
        "metart.com", "hegre.com", "sexart.com",
        "clips4sale.com", "kink.com", "fetlife.com",
        "ixxx.com", "youjizz.com", "xbabe.com",
        "cumlouder.com", "pornhd.com",
        "mature.nl", "goodporn.to", "xxxbunker.com",
        "eroprofile.com", "wetplace.com",
        "spankwire.com", "drtuber.com", "sunporno.com", "ah-me.com",
        "nuvid.com", "pornoxo.com",
        "fapster.xxx", "pornzog.com",
        "lobstertube.com", "pornlib.com",
        "babestube.com", "sexu.com",
        "pandamovies.pw", "bustyporn.xxx",
        "porniq.com", "sexix.net",
        "foxporn.pro", "sextube.com",
        "shemale.com", "tgirls.com", "ladyboygold.com", "trannytube.tv"
    )

    private fun cacheFile(context: Context) = File(context.filesDir, "adult_blocklist.txt")

    fun isCached(context: Context) = cacheFile(context).exists()

    suspend fun loadCached(context: Context): Set<String> = withContext(Dispatchers.IO) {
        try {
            val file = cacheFile(context)
            if (!file.exists()) return@withContext emptySet()
            file.readLines().filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached blocklist: ${e.message}")
            emptySet()
        }
    }

    private suspend fun saveCache(context: Context, domains: Set<String>) = withContext(Dispatchers.IO) {
        try {
            cacheFile(context).writeText(domains.joinToString("\n"))
            Log.d(TAG, "Cached ${domains.size} adult domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save adult blocklist cache: ${e.message}")
        }
    }

    // Downloads from GitHub and caches to filesDir. Returns the domain set (or empty on failure).
    suspend fun downloadAndCache(context: Context): Set<String> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(BLOCKLIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val domains = conn.inputStream.bufferedReader().useLines { lines ->
                lines
                    .filter { it.startsWith("0.0.0.0 ") }
                    .map { it.removePrefix("0.0.0.0 ").trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") && it != "0.0.0.0" }
                    .toSet()
            }
            Log.d(TAG, "Downloaded ${domains.size} adult domains from GitHub")
            if (domains.isNotEmpty()) saveCache(context, domains)
            domains
        } catch (e: Exception) {
            Log.w(TAG, "Adult blocklist download failed: ${e.message}")
            emptySet()
        }
    }
}
