package com.yakumo.xvideo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 一条可下载媒体 */
data class MediaItem(
    val url: String,
    val type: String,          // video / gif / image
    val bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val ext: String = "",      // mp4 / jpg / webp / png；空则按 type 推断
) {
    fun fileExt(): String = when {
        ext.isNotBlank() -> ext
        type == "image" -> when {
            url.contains(".webp", true) -> "webp"
            url.contains(".png", true) -> "png"
            else -> "jpg"
        }
        else -> "mp4"
    }
    fun mimeType(): String = when (fileExt()) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "video/mp4"
    }
    fun kindLabel(): String = when (type) {
        "gif" -> "GIF"
        "image" -> "图片"
        else -> "视频"
    }
}

data class ExtractResult(
    val platform: String,      // twitter / douyin / xiaohongshu
    val id: String,
    val author: String,
    val text: String,
    val media: List<MediaItem>,
    val source: String,
)

class ExtractException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 兼容旧测试命名 */
typealias VideoItem = MediaItem
typealias TweetResult = ExtractResult

object MediaExtractor {

    private const val UA_APP = "MediaDownloader/1.2 (Android; okhttp-compatible)"
    private const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // ---------------- 链接识别 ----------------

    private val TWITTER_HOST =
        "(?:[\\w-]+\\.)*(?:twitter|x|fxtwitter|vxtwitter|fixupx|fixvx|twittpr|nitter|xcancel)\\.(?:com|net)"
    private val TWITTER_STATUS = Regex(
        "https?://$TWITTER_HOST/(?:[^\\s/?#]+/)*status(?:es)?/(\\d{5,25})",
        RegexOption.IGNORE_CASE
    )
    private val TWITTER_NOSCHEME = Regex(
        "(?:^|\\s)$TWITTER_HOST/(?:[^\\s/?#]+/)*status(?:es)?/(\\d{5,25})",
        RegexOption.IGNORE_CASE
    )
    private val TWITTER_ID_ONLY = Regex("^\\d{5,25}$")
    private val TCO = Regex("https?://t\\.co/[A-Za-z0-9]+")

    private val DOUYIN_URL = Regex(
        "https?://(?:[\\w-]+\\.)*(?:douyin\\.com|iesdouyin\\.com|amemv\\.com)/[^\\s]*",
        RegexOption.IGNORE_CASE
    )
    private val DOUYIN_ID = Regex(
        "(?:douyin\\.com|iesdouyin\\.com)/(?:share/)?(?:video|note|slides)/(\\d{6,25})",
        RegexOption.IGNORE_CASE
    )
    private val DOUYIN_MODAL = Regex("[?&]modal_id=(\\d{6,25})", RegexOption.IGNORE_CASE)

    private val XHS_URL = Regex(
        "https?://(?:[\\w-]+\\.)*(?:xiaohongshu\\.com|xhslink\\.com|xhslink\\.cn|xhslink\\.net|rednote\\.com)/[^\\s]*",
        RegexOption.IGNORE_CASE
    )
    private val XHS_SHORT_HOST = Regex(
        "https?://(?:[\\w-]+\\.)*xhslink\\.(?:com|cn|net)/[^\\s]*",
        RegexOption.IGNORE_CASE
    )
    private val XHS_NOTE = Regex(
        "(?:xiaohongshu|rednote)\\.com/(?:explore|discovery/item|item)/([0-9a-f]{24})",
        RegexOption.IGNORE_CASE
    )
    private val XHS_ID_ONLY = Regex("^[0-9a-f]{24}$", RegexOption.IGNORE_CASE)

    enum class Platform { TWITTER, DOUYIN, XIAOHONGSHU, UNKNOWN }

    fun detectPlatform(input: String): Platform {
        val t = input.trim()
        if (DOUYIN_URL.containsMatchIn(t) || t.contains("v.douyin.com", true)) return Platform.DOUYIN
        if (XHS_URL.containsMatchIn(t) || t.contains("xhslink", true) || t.contains("小红书")) {
            // 分享文案常含「小红书」字样 + 链接
            if (XHS_URL.containsMatchIn(t) || XHS_ID_ONLY.matches(t)) return Platform.XIAOHONGSHU
        }
        if (TWITTER_STATUS.containsMatchIn(t) || TWITTER_NOSCHEME.containsMatchIn(t) ||
            TWITTER_ID_ONLY.matches(t) || TCO.containsMatchIn(t)
        ) return Platform.TWITTER
        // 兜底：文案里只有平台关键词 + URL
        if (t.contains("douyin", true) || t.contains("抖音")) return Platform.DOUYIN
        if (t.contains("xiaohongshu", true) || t.contains("xhslink", true) || t.contains("小红书") || t.contains("rednote", true))
            return Platform.XIAOHONGSHU
        return Platform.UNKNOWN
    }

    fun parseTweetId(input: String): String? {
        val text = input.trim()
        if (TWITTER_ID_ONLY.matches(text)) return text
        TWITTER_STATUS.find(text)?.let { return it.groupValues[1] }
        TWITTER_NOSCHEME.find(text)?.let { return it.groupValues[1] }
        return null
    }

    fun findShortLink(input: String): String? = TCO.find(input)?.value

    fun findDouyinUrl(input: String): String? = DOUYIN_URL.find(input)?.value
    fun findXhsUrl(input: String): String? = XHS_URL.find(input)?.value

    /** 小红书 CDN 的 http 链接在部分网络会超时/400，统一升为 https */
    fun preferHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    fun isXhsShortLink(url: String): Boolean = XHS_SHORT_HOST.containsMatchIn(url)

    /**
     * 解析第三方聚合 API 的失败 JSON，生成中文说明；成功返回 null。
     * 兼容 {"code":400,"msg":"..."} 与 {"error":"...","code":503}。
     */
    fun describeApiFailure(json: String, source: String): String? {
        return try {
            val root = JSONObject(json)
            val code = when {
                root.has("code") -> root.opt("code").toString()
                else -> ""
            }
            val msg = root.optString("msg").ifBlank {
                root.optString("message").ifBlank { root.optString("error") }
            }
            val ok = code == "200" || code == "0" || (code.isBlank() && root.optJSONObject("data") != null && msg.isBlank())
            if (ok && root.optJSONObject("data") != null) return null
            // bugpk/hk0 失败常带 error + code
            if (code.isBlank() && msg.isBlank()) return null
            if (code == "200" || code == "0") return null
            val reason = when {
                msg.contains("Unsupported note url host", true) ->
                    "不支持该短链域名（如 xhslink.cn），请改用解析器或先展开为 xiaohongshu.com 链接"
                msg.contains("Invalid note url", true) -> "链接格式无效"
                msg.contains("不可访问", true) || msg.contains("需要登录", true) -> "作品不可访问或需要登录"
                msg.contains("登录已过期", true) || msg.contains("登录态", true) -> "解析服务登录态失效"
                msg.contains("频率", true) || msg.contains("封禁", true) -> "解析服务请求过频或 IP 被限制"
                msg.isNotBlank() -> msg
                else -> "未知原因"
            }
            val codePart = if (code.isNotBlank()) "服务返回 $code" else "服务失败"
            "解析失败（$codePart，$source）：$reason"
        } catch (_: Exception) {
            null
        }
    }


    fun parseDouyinId(text: String): String? {
        DOUYIN_ID.find(text)?.let { return it.groupValues[1] }
        DOUYIN_MODAL.find(text)?.let { return it.groupValues[1] }
        return null
    }

    fun parseXhsNoteId(text: String): String? {
        if (XHS_ID_ONLY.matches(text.trim())) return text.trim()
        return XHS_NOTE.find(text)?.groupValues?.get(1)
    }

    // ---------------- 网络工具 ----------------

    fun httpGet(url: String, userAgent: String = UA_APP, referer: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json,text/html,*/*")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299 && code != 404) throw IOException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** 跟随重定向，返回最终 URL（保留 query） */
    fun resolveRedirects(url: String, userAgent: String = UA_IPHONE, maxHops: Int = 8): String {
        var current = url
        repeat(maxHops) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", userAgent)
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    current = URL(URL(current), loc).toString()
                } else return current
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    fun syndicationToken(id: String): String {
        val x = (id.toDouble() / 1e15) * Math.PI
        return toBase36(x).replace("0", "").replace(".", "")
    }

    private fun toBase36(value: Double): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var ip = value.toLong()
        var fp = BigDecimal(value).subtract(BigDecimal(ip))
        val sb = StringBuilder()
        if (ip == 0L) sb.append('0')
        while (ip > 0) {
            sb.insert(0, digits[(ip % 36).toInt()]); ip /= 36
        }
        sb.append('.')
        val b36 = BigDecimal(36)
        var n = 0
        while (fp.signum() != 0 && n < 20) {
            fp = fp.multiply(b36)
            val d = fp.toInt()
            sb.append(digits[d])
            fp = fp.subtract(BigDecimal(d))
            n++
        }
        return sb.toString()
    }

    // ---------------- Twitter / X ----------------

    fun parseFxTwitter(json: String): ExtractResult? {
        val root = JSONObject(json)
        if (root.optInt("code", 200) != 200) return null
        val tweet = root.optJSONObject("tweet") ?: return null
        var media = fxMedia(tweet)
        if (media.isEmpty()) tweet.optJSONObject("quote")?.let { media = fxMedia(it) }
        return ExtractResult(
            platform = "twitter",
            id = tweet.optString("id"),
            author = tweet.optJSONObject("author")?.optString("screen_name") ?: "",
            text = tweet.optString("text"),
            media = media,
            source = "fxtwitter",
        )
    }

    private fun fxMedia(tweet: JSONObject): List<MediaItem> {
        val media = tweet.optJSONObject("media") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        val videos = media.optJSONArray("videos")
        if (videos != null) for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val type = if (v.optString("type") == "gif") "gif" else "video"
            val best = bestVariant(v.optJSONArray("variants"), "url", "content_type", "bitrate")
                ?: bestFormat(v.optJSONArray("formats"))
            val url = best?.first ?: v.optString("url").takeIf { it.isNotBlank() } ?: continue
            out += MediaItem(url, type, best?.second ?: 0, v.optInt("width"), v.optInt("height"), "mp4")
        }
        val photos = media.optJSONArray("photos") ?: media.optJSONArray("images")
        if (photos != null) for (i in 0 until photos.length()) {
            val p = photos.optJSONObject(i) ?: continue
            val url = p.optString("url").ifBlank { p.optString("media_url_https") }
            if (url.isNotBlank()) out += MediaItem(url, "image", 0, p.optInt("width"), p.optInt("height"))
        }
        return out
    }

    private fun bestFormat(arr: JSONArray?): Pair<String, Long>? {
        if (arr == null) return null
        var best: Pair<String, Long>? = null
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optString("container") != "mp4") continue
            val br = f.optLong("bitrate", 0)
            if (best == null || br > best.second) best = f.optString("url") to br
        }
        return best
    }

    private fun bestVariant(arr: JSONArray?, urlKey: String, typeKey: String, brKey: String): Pair<String, Long>? {
        if (arr == null) return null
        var best: Pair<String, Long>? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ct = o.optString(typeKey)
            val url = o.optString(urlKey)
            if (ct != "video/mp4" && !url.contains(".mp4")) continue
            val br = o.optLong(brKey, o.optLong("bit_rate", 0))
            if (best == null || br > best.second) best = url to br
        }
        return best
    }

    fun parseVxTwitter(json: String): ExtractResult? {
        val root = JSONObject(json)
        val out = ArrayList<MediaItem>()
        val ext = root.optJSONArray("media_extended")
        if (ext != null) {
            for (i in 0 until ext.length()) {
                val m = ext.optJSONObject(i) ?: continue
                val t = m.optString("type")
                when (t) {
                    "video", "gif" -> {
                        val size = m.optJSONObject("size")
                        out += MediaItem(
                            m.optString("url"), t, 0,
                            size?.optInt("width") ?: 0, size?.optInt("height") ?: 0, "mp4"
                        )
                    }
                    "image", "photo" -> out += MediaItem(m.optString("url"), "image")
                }
            }
        } else {
            val urls = root.optJSONArray("mediaURLs")
            if (urls != null) for (i in 0 until urls.length()) {
                val u = urls.optString(i)
                when {
                    u.contains("video.twimg.com") ->
                        out += MediaItem(u, if (u.contains("tweet_video")) "gif" else "video", ext = "mp4")
                    u.contains("pbs.twimg.com") || u.contains("media") ->
                        out += MediaItem(u, "image")
                }
            }
        }
        if (!root.has("tweetID") && out.isEmpty()) return null
        return ExtractResult("twitter", root.optString("tweetID"), root.optString("user_screen_name"),
            root.optString("text"), out, "vxtwitter")
    }

    fun parseSyndication(json: String): ExtractResult? {
        val root = JSONObject(json)
        if (root.optString("__typename") == "TweetTombstone") return null
        val details = root.optJSONArray("mediaDetails")
        val out = ArrayList<MediaItem>()
        if (details != null) for (i in 0 until details.length()) {
            val m = details.optJSONObject(i) ?: continue
            when (m.optString("type")) {
                "video", "animated_gif" -> {
                    val best = bestVariant(
                        m.optJSONObject("video_info")?.optJSONArray("variants"),
                        "url", "content_type", "bitrate"
                    ) ?: continue
                    val info = m.optJSONObject("original_info")
                    out += MediaItem(
                        best.first, if (m.optString("type") == "animated_gif") "gif" else "video",
                        best.second, info?.optInt("width") ?: 0, info?.optInt("height") ?: 0, "mp4"
                    )
                }
                "photo" -> {
                    val url = m.optString("media_url_https").ifBlank { m.optString("media_url") }
                    if (url.isNotBlank()) {
                        val info = m.optJSONObject("original_info")
                        out += MediaItem(url, "image", 0, info?.optInt("width") ?: 0, info?.optInt("height") ?: 0)
                    }
                }
            }
        }
        if (!root.has("id_str")) return null
        return ExtractResult(
            "twitter", root.optString("id_str"),
            root.optJSONObject("user")?.optString("screen_name") ?: "",
            root.optString("text"), out, "syndication"
        )
    }

    private fun extractTwitter(input: String, log: (String) -> Unit): ExtractResult {
        var id = parseTweetId(input)
        if (id == null) {
            findShortLink(input)?.let {
                try { id = parseTweetId(resolveRedirects(it, UA_APP)) } catch (_: Exception) {}
            }
        }
        val tweetId = id ?: throw ExtractException("链接无效：请粘贴 X / Twitter 帖子链接，例如 https://x.com/用户名/status/123456")

        val sources: List<Pair<String, () -> ExtractResult?>> = listOf(
            "fxtwitter" to { parseFxTwitter(httpGet("https://api.fxtwitter.com/status/$tweetId")) },
            "vxtwitter" to { parseVxTwitter(httpGet("https://api.vxtwitter.com/Twitter/status/$tweetId")) },
            "syndication" to {
                parseSyndication(
                    httpGet(
                        "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=zh-cn&token=${syndicationToken(tweetId)}"
                    )
                )
            },
        )
        return firstMedia(sources, log, "帖子")
    }

    // ---------------- 抖音 ----------------

    /** 从 iesdouyin 分享页 HTML 解析 _ROUTER_DATA */
    fun parseDouyinShareHtml(html: String, awemeId: String = ""): ExtractResult? {
        val marker = "window._ROUTER_DATA"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val eq = html.indexOf('=', idx)
        if (eq < 0) return null
        val end = html.indexOf("</script>", eq)
        if (end < 0) return null
        var raw = html.substring(eq + 1, end).trim().trimEnd(';')
        val brace = raw.indexOf('{')
        if (brace >= 0) raw = raw.substring(brace)
        val root = try { JSONObject(raw) } catch (_: Exception) { return null }
        val page = root.optJSONObject("loaderData")?.optJSONObject("video_(id)/page")
            ?: root.optJSONObject("loaderData")?.optJSONObject("note_(id)/page")
            ?: return null
        val vir = page.optJSONObject("videoInfoRes") ?: return null
        val items = vir.optJSONArray("item_list") ?: vir.optJSONArray("filter_list") ?: return null
        if (items.length() == 0) return null
        val item = items.getJSONObject(0)
        return douyinItemToResult(item, "iesdouyin-share", awemeId.ifBlank { item.optString("aweme_id") })
    }

    private fun douyinItemToResult(item: JSONObject, source: String, idHint: String): ExtractResult {
        val out = ArrayList<MediaItem>()
        val images = item.optJSONArray("images")
        if (images != null && images.length() > 0) {
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                val urls = img.optJSONArray("url_list")
                val url = urls?.optString(0)?.ifBlank { null }
                    ?: img.optJSONObject("download_url_list")?.optJSONArray("url_list")?.optString(0)
                    ?: img.optString("url")
                if (!url.isNullOrBlank()) {
                    out += MediaItem(url, "image", 0, img.optInt("width"), img.optInt("height"))
                }
            }
        }
        if (out.isEmpty()) {
            val video = item.optJSONObject("video")
            val play = video?.optJSONObject("play_addr")
            val uri = play?.optString("uri").orEmpty()
            var url = play?.optJSONArray("url_list")?.optString(0).orEmpty()
            if (url.contains("playwm")) url = url.replace("playwm", "play")
            if (url.isBlank() && uri.isNotBlank()) {
                url = "https://www.iesdouyin.com/aweme/v1/play/?video_id=$uri&ratio=1080p&line=0"
            }
            if (url.isNotBlank()) {
                out += MediaItem(
                    url, "video", 0,
                    video?.optInt("width") ?: 0,
                    video?.optInt("height") ?: 0, "mp4"
                )
            }
        }
        val author = item.optJSONObject("author")?.optString("nickname")
            ?: item.optJSONObject("author")?.optString("unique_id") ?: ""
        return ExtractResult(
            "douyin",
            idHint.ifBlank { item.optString("aweme_id") },
            author,
            item.optString("desc"),
            out,
            source
        )
    }

    /** 解析 hk0 / juhev / mu-jie 一类聚合 JSON；失败时返回 null（可用 describeApiFailure 读原因） */
    fun parseHk0Style(json: String, platform: String, source: String): ExtractResult? {
        val root = JSONObject(json)
        // bugpk 风格：{"error":"...","code":503} 无 data
        if (root.has("error") && !root.has("data")) return null
        val code = root.optInt("code", -999)
        // mu-jie / hk0 成功为 200；有的接口用 0
        if (code != -999 && code != 200 && code != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val out = ArrayList<MediaItem>()

        // images: ["url"] or [{url}]
        val images = data.optJSONArray("images")
        if (images != null) for (i in 0 until images.length()) {
            val u = when {
                images.optJSONObject(i) != null -> {
                    val o = images.getJSONObject(i)
                    o.optString("url").ifBlank { o.optString("url_default") }
                }
                else -> images.optString(i)
            }
            if (u.isNotBlank()) out += MediaItem(preferHttps(u), "image")
        }
        // live_photo: may contain video urls
        val live = data.optJSONArray("live_photo")
        if (live != null) for (i in 0 until live.length()) {
            val o = live.optJSONObject(i) ?: continue
            val vu = o.optString("video").ifBlank { o.optString("url") }
            if (vu.isNotBlank() && (vu.contains(".mp4") || vu.contains("video"))) {
                out += MediaItem(preferHttps(vu), "video", ext = "mp4")
            } else if (vu.isNotBlank()) {
                out += MediaItem(preferHttps(vu), "image")
            }
        }

        // video url — prefer data.url (常为原画), 否则选码率/分辨率最高的 backup
        var videoUrl = data.optString("url")
        var bw = 0; var bh = 0; var bbr = 0L
        var bestBackup = ""
        val backups = data.optJSONArray("video_backup")
        if (backups != null) for (i in 0 until backups.length()) {
            val b = backups.optJSONObject(i) ?: continue
            val u = b.optString("url")
            if (u.isBlank()) continue
            val br = b.optLong("bit_rate", b.optLong("bitrate", 0))
            val area = b.optInt("width") * b.optInt("height").toLong()
            val score = if (br > 0) br else area
            val bestScore = if (bbr > 0) bbr else bw.toLong() * bh
            if (bestBackup.isBlank() || score > bestScore) {
                bestBackup = u; bbr = br; bw = b.optInt("width"); bh = b.optInt("height")
            }
        }
        if (videoUrl.isBlank()) videoUrl = bestBackup
        // 若声明 quality=original 但 url 空，用 backup
        if (videoUrl.isBlank()) videoUrl = data.optString("video")
        // 若已有图集，通常不把封面当视频；仅 type=video 时加视频
        val type = data.optString("type")
        if (videoUrl.isNotBlank() && (type == "video" || out.none { it.type == "image" })) {
            // 避免把图片链接当视频
            if (!videoUrl.contains("douyinpic.com") && !videoUrl.contains("xhscdn.com") ||
                videoUrl.contains(".mp4") || videoUrl.contains("video") || videoUrl.contains("play")
            ) {
                if (type != "image" && type != "images" && type != "note" && type != "图文" && type != "normal") {
                    out.add(0, MediaItem(preferHttps(videoUrl), "video", bbr, bw, bh, "mp4"))
                }
            }
        }
        // 图文时 url 可能为空，images 已处理
        if (out.isEmpty() && videoUrl.isNotBlank()) {
            val isImg = type == "image" || type == "图文" || type == "normal"
            out += MediaItem(preferHttps(videoUrl), if (isImg) "image" else "video", bbr, bw, bh)
        }

        // author 可能是对象或纯字符串（mu-jie）
        val author = when {
            data.optJSONObject("author") != null -> {
                val o = data.getJSONObject("author")
                o.optString("name").ifBlank { o.optString("nickname") }
            }
            else -> data.optString("author")
        }
        val id = data.optJSONObject("extra")?.optString("aweme_id")
            ?: data.optString("aweme_id").ifBlank { data.optString("note_id") }
                .ifBlank { data.optString("id") }
        val title = data.optString("title").ifBlank { data.optString("desc") }
        if (out.isEmpty() && id.isBlank() && title.isBlank()) return null
        return ExtractResult(platform, id, author, title, out, source)
    }

    private fun extractDouyin(input: String, log: (String) -> Unit): ExtractResult {
        var url = findDouyinUrl(input)
        if (url == null && input.contains("v.douyin.com", true)) {
            // 可能缺协议
            val m = Regex("v\\.douyin\\.com/[A-Za-z0-9_-]+/?", RegexOption.IGNORE_CASE).find(input)
            if (m != null) url = "https://${m.value}"
        }
        if (url == null && parseDouyinId(input) == null) {
            throw ExtractException("链接无效：请粘贴抖音分享链接，例如 https://v.douyin.com/xxxx/")
        }

        var awemeId = url?.let { parseDouyinId(it) } ?: parseDouyinId(input)
        var finalUrl = url
        if (url != null && (url.contains("v.douyin.com") || awemeId == null)) {
            try {
                finalUrl = resolveRedirects(url, UA_IPHONE)
                awemeId = parseDouyinId(finalUrl) ?: awemeId
                log("抖音短链解析 → $awemeId")
            } catch (e: Exception) {
                log("短链跳转失败: ${e.message}")
            }
        }
        if (awemeId == null) throw ExtractException("无法从抖音链接中识别作品 ID，请换一条分享链接重试")

        val sources = ArrayList<Pair<String, () -> ExtractResult?>>()
        sources += "iesdouyin-share" to {
            val html = httpGet("https://www.iesdouyin.com/share/video/$awemeId/", UA_IPHONE, "https://www.douyin.com/")
            parseDouyinShareHtml(html, awemeId)
        }
        val encoded = URLEncoder.encode(url ?: "https://www.douyin.com/video/$awemeId", "UTF-8")
        sources += "hk0-douyin" to {
            parseHk0Style(httpGet("https://api.hk0.cc/api/douyin?url=$encoded"), "douyin", "hk0-douyin")
        }
        sources += "hk0-juhev" to {
            parseHk0Style(httpGet("https://api.hk0.cc/api/juhev?url=$encoded"), "douyin", "hk0-juhev")
        }
        return firstMedia(sources, log, "作品")
    }

    // ---------------- 小红书 ----------------

    fun parseXhsInitialState(html: String, noteIdHint: String = ""): ExtractResult? {
        val marker = "window.__INITIAL_STATE__"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val eq = html.indexOf('=', idx)
        if (eq < 0) return null
        val end = html.indexOf("</script>", eq)
        if (end < 0) return null
        var raw = html.substring(eq + 1, end).trim().trimEnd(';')
        raw = raw.replace(Regex("\\bundefined\\b"), "null")
        raw = raw.replace(Regex("new\\s+Map\\([^)]*\\)"), "{}")
        raw = raw.replace(Regex("new\\s+Set\\([^)]*\\)"), "[]")
        val root = try { JSONObject(raw) } catch (_: Exception) { return null }

        // PC: note.noteDetailMap[id].note
        var note: JSONObject? = null
        val noteWrap = root.optJSONObject("note")
        val map = noteWrap?.optJSONObject("noteDetailMap")
        if (map != null) {
            val id = noteIdHint.ifBlank { noteWrap.optString("firstNoteId") }
            if (id.isNotBlank() && map.has(id)) {
                note = map.optJSONObject(id)?.optJSONObject("note")
            }
            if (note == null) {
                val keys = map.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val n = map.optJSONObject(k)?.optJSONObject("note")
                    if (n != null) { note = n; break }
                }
            }
        }
        // H5: noteData.data.noteData
        if (note == null) {
            note = root.optJSONObject("noteData")?.optJSONObject("data")?.optJSONObject("noteData")
        }
        if (note == null) return null

        val out = ArrayList<MediaItem>()
        val type = note.optString("type")
        val video = note.optJSONObject("video")
        var hasVideo = false
        if (video != null) {
            val url = pickXhsVideoUrl(video)
            if (url != null) {
                out += MediaItem(preferHttps(url), "video", ext = "mp4")
                hasVideo = true
            }
        }
        // 视频笔记：只下视频（imageList 多为封面）；图文/实况：下全部图片
        if (!hasVideo || type == "normal" || type == "image") {
            val imageList = note.optJSONArray("imageList") ?: note.optJSONArray("images_list")
            if (imageList != null) for (i in 0 until imageList.length()) {
                val img = imageList.optJSONObject(i) ?: continue
                val url = pickXhsImageUrl(img) ?: continue
                out += MediaItem(preferHttps(url), "image", 0, img.optInt("width"), img.optInt("height"))
            }
        }

        val user = note.optJSONObject("user")
        val author = user?.optString("nickname")?.ifBlank { user.optString("nick_name") } ?: ""
        val id = note.optString("noteId").ifBlank { note.optString("id") }.ifBlank { noteIdHint }
        val text = note.optString("title").ifBlank { note.optString("desc") }
        if (out.isEmpty()) return null
        return ExtractResult("xiaohongshu", id, author, text, dedupeMedia(out, preferVideo = hasVideo), "xhs-html")
    }

    private fun pickXhsVideoUrl(video: JSONObject): String? {
        // video.media.stream.h264[0].masterUrl
        val stream = video.optJSONObject("media")?.optJSONObject("stream")
            ?: video.optJSONObject("consumer") // older
        if (stream != null) {
            for (codec in listOf("h264", "h265", "av1")) {
                val arr = stream.optJSONArray(codec) ?: continue
                if (arr.length() == 0) continue
                val first = arr.optJSONObject(0) ?: continue
                val u = first.optString("masterUrl").ifBlank {
                    first.optJSONArray("backupUrls")?.optString(0) ?: ""
                }
                if (u.isNotBlank()) return u
            }
        }
        val url = video.optString("url").ifBlank { video.optJSONObject("url")?.toString() ?: "" }
        return url.ifBlank { null }
    }

    private fun pickXhsImageUrl(img: JSONObject): String? {
        val info = img.optJSONArray("infoList")
        if (info != null) {
            var dft: String? = null
            var any: String? = null
            for (i in 0 until info.length()) {
                val o = info.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                any = u
                if (o.optString("imageScene") == "WB_DFT") dft = u
            }
            if (dft != null) return dft
            if (any != null) return any
        }
        return img.optString("urlDefault").ifBlank {
            img.optString("url_default").ifBlank {
                img.optString("url").ifBlank { img.optString("original") }
            }
        }.ifBlank { null }
    }

    /** 视频笔记：优先保留视频，图片仍全部保留（去重 URL） */
    private fun dedupeMedia(list: List<MediaItem>, preferVideo: Boolean): List<MediaItem> {
        val seen = HashSet<String>()
        val out = ArrayList<MediaItem>()
        val ordered = if (preferVideo) list.sortedBy { if (it.type == "video") 0 else 1 } else list
        for (m in ordered) {
            val key = m.url.substringBefore('?')
            if (seen.add(key)) out += m
        }
        return out
    }

    /** 跟随短链但停在笔记页（不进入 captcha/login） */
    fun resolveXhsToNotePage(shortUrl: String): String {
        var current = shortUrl
        repeat(8) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", UA_IPHONE)
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    val next = URL(URL(current), loc).toString()
                    // 已到达笔记页：保留带 xsec_token 的地址，不再跟到验证码
                    if (parseXhsNoteId(next) != null &&
                        (next.contains("/explore/") || next.contains("/discovery/item/") || next.contains("/item/"))
                    ) {
                        return next
                    }
                    // 验证码/登录页：从 redirectPath 还原笔记 URL
                    if (next.contains("captcha") || next.contains("website-login") || next.contains("/404")) {
                        val m = Regex("redirectPath=([^&]+)").find(next)
                        if (m != null) {
                            val decoded = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                            if (parseXhsNoteId(decoded) != null) return decoded
                        }
                        // 仍尽量从 Location 文本里抠 noteId
                        parseXhsNoteId(next)?.let { id ->
                            return "https://www.xiaohongshu.com/discovery/item/$id"
                        }
                        return current
                    }
                    current = next
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun extractXiaohongshu(input: String, log: (String) -> Unit): ExtractResult {
        var url = findXhsUrl(input)
        if (url == null) {
            val bare = Regex("xhslink\\.(?:com|cn|net)/[A-Za-z0-9/]+", RegexOption.IGNORE_CASE).find(input)
            if (bare != null) url = "https://${bare.value}"
        }
        // 去掉分享文案尾部中文标点粘连
        if (url != null) {
            url = url.trimEnd('。', '，', '、', '）', ')', '】', '>', '》')
        }
        val noteIdHint = parseXhsNoteId(input)
        if (url == null && noteIdHint == null) {
            throw ExtractException("链接无效：请粘贴小红书分享链接（xhslink.com / xhslink.cn 短链或笔记页链接）")
        }

        var pageUrl = url
        var noteId = noteIdHint
        if (url != null && isXhsShortLink(url)) {
            try {
                pageUrl = resolveXhsToNotePage(url)
                noteId = parseXhsNoteId(pageUrl) ?: noteId
                log("小红书短链 → $pageUrl")
            } catch (e: Exception) {
                log("短链跳转失败: ${e.message}")
            }
        }
        if (pageUrl == null && noteId != null) {
            pageUrl = "https://www.xiaohongshu.com/explore/$noteId"
        }

        val apiErrors = ArrayList<String>()
        fun tryApi(name: String, apiUrl: String): ExtractResult? {
            val body = httpGet(apiUrl)
            describeApiFailure(body, name)?.let { apiErrors += it; log(it); return null }
            val r = parseHk0Style(body, "xiaohongshu", name)
            if (r == null) log("$name: 未解析到媒体")
            else if (r.id.isBlank() && noteId != null) {
                return r.copy(id = noteId)
            }
            return r
        }

        val sources = ArrayList<Pair<String, () -> ExtractResult?>>()
        // 优先 mu-jie：明确支持 xhslink.cn，且对本笔记实测可用
        val shortOrPage = url ?: pageUrl ?: input
        val encShort = URLEncoder.encode(shortOrPage, "UTF-8")
        sources += "mujie-xhs" to { tryApi("mujie-xhs", "https://api.mu-jie.cc/xhs?url=$encShort") }

        // HTML（中国手机网络上有时可用；机房 IP 常被验证码拦截）
        if (pageUrl != null && parseXhsNoteId(pageUrl) != null) {
            val pu = pageUrl
            sources += "xhs-html-mobile" to {
                val html = httpGet(pu, UA_IPHONE, "https://www.xiaohongshu.com/")
                if (html.contains("captcha") || html.contains("website-login")) {
                    log("xhs-html-mobile: 触发验证码"); null
                } else parseXhsInitialState(html, noteId ?: "")
            }
            sources += "xhs-html-pc" to {
                val html = httpGet(pu, UA_PC, "https://www.xiaohongshu.com/")
                parseXhsInitialState(html, noteId ?: "")
            }
        }

        // bugpk 不支持 xhslink.cn → 用展开后的 xiaohongshu.com 链接调用
        val longForBugpk = when {
            pageUrl != null && parseXhsNoteId(pageUrl) != null -> pageUrl
            else -> shortOrPage
        }
        val encLong = URLEncoder.encode(longForBugpk!!, "UTF-8")
        sources += "hk0-xhs" to { tryApi("hk0-xhs", "https://api.hk0.cc/api/xhs?url=$encLong") }
        sources += "hk0-juhev" to { tryApi("hk0-juhev", "https://api.hk0.cc/api/juhev?url=$encShort") }
        sources += "bugpk-xhs" to { tryApi("bugpk-xhs", "https://api.bugpk.com/api/xhs?url=$encLong") }

        try {
            val r = firstMedia(sources, log, "笔记")
            return if (r.id.isBlank() && noteId != null) r.copy(id = noteId) else r
        } catch (e: ExtractException) {
            val detail = apiErrors.lastOrNull()
            if (detail != null) {
                throw ExtractException(
                    "$detail。也可在小红书 App 重新复制分享链接后重试。",
                    e
                )
            }
            throw ExtractException(
                "无法获取该小红书笔记：可能被风控、需要登录，或短链已失效。请用 App 分享的 xhslink.com / xhslink.cn 完整短链重试。",
                e
            )
        }
    }

    // ---------------- 统一入口 ----------------

    private fun firstMedia(
        sources: List<Pair<String, () -> ExtractResult?>>,
        log: (String) -> Unit,
        itemWord: String,
    ): ExtractResult {
        var networkErrors = 0
        var foundEmpty: ExtractResult? = null
        for ((name, fn) in sources) {
            try {
                val r = fn()
                if (r == null) {
                    log("$name: 未找到$itemWord"); continue
                }
                if (r.media.isNotEmpty()) return r
                log("$name: ${itemWord}中没有可下载媒体")
                foundEmpty = r
            } catch (e: IOException) {
                networkErrors++
                log("$name: 网络错误 ${e.message}")
            } catch (e: ExtractException) {
                // 单源失败不中断整条链路（最终由汇总错误提示用户）
                log("$name: ${e.message}")
            } catch (e: Exception) {
                log("$name: 解析失败 ${e.message}")
            }
        }
        if (foundEmpty != null) throw ExtractException("这条内容里没有可下载的视频或图片")
        if (networkErrors == sources.size) {
            throw ExtractException("网络连接失败，请检查网络后重试")
        }
        throw ExtractException("无法获取该内容：可能已删除、为私密/受保护账号，或触发了平台风控")
    }

    /**
     * 自动识别平台并提取媒体。
     */
    fun extract(input: String, log: (String) -> Unit = {}): ExtractResult {
        val text = input.trim()
        if (text.isEmpty()) throw ExtractException("请先输入或粘贴链接")
        return when (detectPlatform(text)) {
            Platform.DOUYIN -> extractDouyin(text, log)
            Platform.XIAOHONGSHU -> extractXiaohongshu(text, log)
            Platform.TWITTER -> extractTwitter(text, log)
            Platform.UNKNOWN -> {
                // 再尝试各平台 id 启发式
                when {
                    parseDouyinId(text) != null -> extractDouyin(text, log)
                    parseXhsNoteId(text) != null -> extractXiaohongshu(text, log)
                    parseTweetId(text) != null -> extractTwitter(text, log)
                    else -> throw ExtractException(
                        "无法识别链接：请粘贴 X / 小红书 / 抖音 的作品分享链接或完整分享文案"
                    )
                }
            }
        }
    }
}

/** 兼容旧名 */
object TweetExtractor {
    fun parseTweetId(input: String) = MediaExtractor.parseTweetId(input)
    fun findShortLink(input: String) = MediaExtractor.findShortLink(input)
    fun syndicationToken(id: String) = MediaExtractor.syndicationToken(id)
    fun parseFxTwitter(json: String) = MediaExtractor.parseFxTwitter(json)
    fun parseVxTwitter(json: String) = MediaExtractor.parseVxTwitter(json)
    fun parseSyndication(json: String) = MediaExtractor.parseSyndication(json)
    fun httpGet(url: String) = MediaExtractor.httpGet(url)
    fun resolveShortLink(url: String) = MediaExtractor.resolveRedirects(url)
    fun extract(input: String, log: (String) -> Unit = {}) = MediaExtractor.extract(input, log)
}
