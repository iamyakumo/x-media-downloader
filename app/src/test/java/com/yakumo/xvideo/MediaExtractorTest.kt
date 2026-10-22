package com.yakumo.xvideo

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class MediaExtractorTest {

    private val TW_ID = "1585341984679469056"
    private val TW_URL = "https://x.com/elonmusk/status/$TW_ID?s=46&t=abcDEF123"
    private val DY_SHORT = "https://v.douyin.com/_7K_L3Mu-44/"
    private val DY_ID = "7634107283886148915"

    @Test fun detectsPlatforms() {
        assertEquals(MediaExtractor.Platform.TWITTER, MediaExtractor.detectPlatform(TW_URL))
        assertEquals(MediaExtractor.Platform.DOUYIN, MediaExtractor.detectPlatform(DY_SHORT))
        assertEquals(
            MediaExtractor.Platform.DOUYIN,
            MediaExtractor.detectPlatform("3.89 复制打开抖音，看看视频 $DY_SHORT 复制此链接")
        )
        assertEquals(
            MediaExtractor.Platform.XIAOHONGSHU,
            MediaExtractor.detectPlatform("http://xhslink.com/o/8G529qPH5ZL")
        )
        assertEquals(
            MediaExtractor.Platform.XIAOHONGSHU,
            MediaExtractor.detectPlatform("https://www.xiaohongshu.com/explore/671e2c6500000000210016b9")
        )
        assertEquals(
            MediaExtractor.Platform.XIAOHONGSHU,
            MediaExtractor.detectPlatform("67 小红书粉丝你好 http://xhslink.com/o/abcXYZ 复制后打开")
        )
        assertEquals(MediaExtractor.Platform.UNKNOWN, MediaExtractor.detectPlatform("https://www.youtube.com/watch?v=1"))
    }

    @Test fun parsesTwitterLinkForms() {
        val forms = listOf(
            "https://x.com/elonmusk/status/$TW_ID",
            "https://twitter.com/elonmusk/status/$TW_ID?s=20",
            "https://mobile.twitter.com/elonmusk/status/$TW_ID",
            "https://fxtwitter.com/elonmusk/status/$TW_ID",
            "https://vxtwitter.com/elonmusk/status/$TW_ID/video/1",
            "https://x.com/i/web/status/$TW_ID",
            "x.com/elonmusk/status/$TW_ID",
            "看看这个 https://x.com/elonmusk/status/$TW_ID?s=46 太好笑了",
            TW_ID,
        )
        for (f in forms) assertEquals(f, TW_ID, MediaExtractor.parseTweetId(f))
    }

    @Test fun parsesDouyinAndXhsIds() {
        assertEquals(DY_ID, MediaExtractor.parseDouyinId("https://www.iesdouyin.com/share/video/$DY_ID/?region=CN"))
        assertEquals(DY_ID, MediaExtractor.parseDouyinId("https://www.douyin.com/video/$DY_ID"))
        assertEquals(DY_ID, MediaExtractor.parseDouyinId("https://www.douyin.com/jingxuan?modal_id=$DY_ID"))
        assertEquals(
            "671e2c6500000000210016b9",
            MediaExtractor.parseXhsNoteId("https://www.xiaohongshu.com/explore/671e2c6500000000210016b9")
        )
        assertEquals(
            "67f75b07000000000b0154b3",
            MediaExtractor.parseXhsNoteId("https://www.xiaohongshu.com/discovery/item/67f75b07000000000b0154b3?xsec_token=abc")
        )
    }

    @Test fun tokenMatchesReference() {
        assertEquals("3uchycv2wqcdgexol59udi", MediaExtractor.syndicationToken(TW_ID))
    }

    @Test fun twitterFxPicksBestAndGif() {
        val json = """
        {"code":200,"tweet":{"id":"1","text":"t","author":{"screen_name":"u"},
          "media":{"videos":[
            {"type":"video","url":"https://video.twimg.com/a/low.mp4","width":1920,"height":1080,
             "variants":[
               {"url":"https://video.twimg.com/a/pl.m3u8","bitrate":0,"content_type":"application/x-mpegURL"},
               {"url":"https://video.twimg.com/a/480.mp4","bitrate":256000,"content_type":"video/mp4"},
               {"url":"https://video.twimg.com/a/1080.mp4","bitrate":10368000,"content_type":"video/mp4"}]},
            {"type":"gif","url":"https://video.twimg.com/tweet_video/G.mp4","width":400,"height":300}
          ],"photos":[{"url":"https://pbs.twimg.com/media/PHOTO.jpg","width":100,"height":100}]}}}"""
        val r = MediaExtractor.parseFxTwitter(json)!!
        assertEquals(3, r.media.size)
        assertEquals("https://video.twimg.com/a/1080.mp4", r.media[0].url)
        assertEquals("gif", r.media[1].type)
        assertEquals("image", r.media[2].type)
    }

    @Test fun douyinShareHtmlParsesVideoAndImages() {
        val html = """
        <html><script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{
          "aweme_id":"111","desc":"你好","author":{"nickname":"作者"},
          "video":{"play_addr":{"uri":"v123","url_list":["https://example.com/playwm/x.mp4"]},
                   "width":1080,"height":1920}
        }]}}}};</script></html>"""
        val r = MediaExtractor.parseDouyinShareHtml(html, "111")!!
        assertEquals("douyin", r.platform)
        assertEquals(1, r.media.size)
        assertTrue(r.media[0].url.contains("play/")) // playwm -> play
        assertFalse(r.media[0].url.contains("playwm"))

        val htmlImg = """
        <html><script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{
          "aweme_id":"222","desc":"图集","author":{"nickname":"A"},
          "images":[
            {"url_list":["https://p3.douyinpic.com/a.jpeg"],"width":1080,"height":1440},
            {"url_list":["https://p3.douyinpic.com/b.jpeg"],"width":1080,"height":1440}
          ]
        }]}}}};</script></html>"""
        val img = MediaExtractor.parseDouyinShareHtml(htmlImg)!!
        assertEquals(2, img.media.size)
        assertTrue(img.media.all { it.type == "image" })
    }

    @Test fun hk0StyleParsesDouyinVideoAndXhsImages() {
        val dy = """{"code":200,"msg":"ok","data":{"type":"video","title":"t","author":{"name":"u"},
          "url":"https://cdn.example/orig.mp4","video_backup":[
            {"quality":"720p","url":"https://cdn.example/720.mp4","bit_rate":800000,"width":720,"height":1280},
            {"quality":"1080p","url":"https://cdn.example/1080.mp4","bit_rate":2000000,"width":1080,"height":1920}
          ],"images":[]}}"""
        val r = MediaExtractor.parseHk0Style(dy, "douyin", "hk0")!!
        assertEquals("https://cdn.example/orig.mp4", r.media.single().url)

        val xhs = """{"code":200,"data":{"type":"image","title":"笔记","author":{"name":"红薯"},
          "images":["https://sns-webpic.xhscdn.com/1.jpg","https://sns-webpic.xhscdn.com/2.webp"],
          "url":""}}"""
        val x = MediaExtractor.parseHk0Style(xhs, "xiaohongshu", "hk0")!!
        assertEquals(2, x.media.size)
        assertEquals("image", x.media[0].type)
        assertEquals("webp", x.media[1].fileExt())
    }

    @Test fun xhsInitialStateParsesVideoAndCarousel() {
        val html = """
        <html><script>window.__INITIAL_STATE__={
          "note":{"firstNoteId":"abcabcabcabcabcabcabcabc",
            "noteDetailMap":{"abcabcabcabcabcabcabcabc":{"note":{
              "noteId":"abcabcabcabcabcabcabcabc","type":"video","title":"测","desc":"描",
              "user":{"nickname":"作者"},
              "video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video.xhscdn.com/a.mp4","backupUrls":[]}]}}},
              "imageList":[{"width":1080,"height":1440,"infoList":[
                {"imageScene":"WB_DFT","url":"https://sns-webpic.xhscdn.com/cover.jpg"}]}]
            }}}
          }
        }</script></html>"""
        val r = MediaExtractor.parseXhsInitialState(html)!!
        assertEquals("xiaohongshu", r.platform)
        assertEquals(1, r.media.size)
        assertTrue(r.media.single().type == "video" && r.media.single().url.endsWith(".mp4"))

        val imgHtml = """
        <html><script>window.__INITIAL_STATE__={
          "note":{"firstNoteId":"dddddddddddddddddddddddd",
            "noteDetailMap":{"dddddddddddddddddddddddd":{"note":{
              "noteId":"dddddddddddddddddddddddd","type":"normal","title":"图文",
              "user":{"nickname":"U"},
              "imageList":[
                {"urlDefault":"https://sns-webpic.xhscdn.com/1.jpg","width":1,"height":1},
                {"urlDefault":"https://sns-webpic.xhscdn.com/2.jpg","width":1,"height":1},
                {"urlDefault":"https://sns-webpic.xhscdn.com/3.jpg","width":1,"height":1}
              ]
            }}}
          }
        }</script></html>"""
        val imgs = MediaExtractor.parseXhsInitialState(imgHtml)!!
        assertEquals(3, imgs.media.size)
        assertTrue(imgs.media.all { it.type == "image" })
    }

    // -------- 在线测试 --------

    private fun online() = Assume.assumeTrue(System.getenv("OFFLINE") == null)

    private fun assertDownloadable(url: String, expectVideo: Boolean = true) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 20000
                c.requestMethod = "GET"
                c.setRequestProperty("Range", "bytes=0-1023")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MediaDownloader/1.1")
                c.setRequestProperty(
                    "Referer",
                    when {
                        url.contains("twimg.com") -> "https://x.com/"
                        url.contains("xhscdn") || url.contains("xiaohongshu") -> "https://www.xiaohongshu.com/"
                        else -> "https://www.douyin.com/"
                    }
                )
                val code = c.responseCode
                val ct = (c.contentType ?: "").lowercase()
                val head = c.inputStream.use { it.readNBytes(16) }
                c.disconnect()
                println("  -> HTTP $code ct=$ct head0=${head.take(8).joinToString("") { "%02x".format(it) }} (try ${attempt + 1})")
                assertTrue("HTTP $code", code == 200 || code == 206)
                if (expectVideo) {
                    assertTrue(
                        "not video: $ct",
                        ct.contains("video") || ct.contains("octet") || (head.size >= 8 && String(head, 4, 4) == "ftyp")
                    )
                    if (head.size >= 8 && ct.contains("video")) assertEquals("ftyp", String(head, 4, 4))
                } else {
                    assertTrue(
                        "not image: $ct",
                        ct.contains("image") || head[0] == 0xFF.toByte() || head[0] == 0x89.toByte() ||
                            (head.size >= 4 && String(head, 0, 4) == "RIFF")
                    )
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw last ?: AssertionError("download check failed")
    }

    @Test fun liveTwitter() {
        online()
        val r = MediaExtractor.extract(TW_URL)
        println("twitter: source=${r.source} media=${r.media}")
        assertEquals("twitter", r.platform)
        assertTrue(r.media.isNotEmpty())
        assertDownloadable(r.media.first().url, expectVideo = true)
    }

    @Test fun liveDouyinShortLink() {
        online()
        val logs = ArrayList<String>()
        val r = MediaExtractor.extract(DY_SHORT) { logs += it }
        println("douyin: source=${r.source} author=${r.author} media=${r.media} logs=$logs")
        assertEquals("douyin", r.platform)
        assertTrue(r.media.isNotEmpty())
        assertEquals("video", r.media.first().type)
        assertDownloadable(r.media.first().url, expectVideo = true)
    }

    @Test fun liveDouyinShareText() {
        online()
        val text = "4.33 复制打开抖音，看看【小植物大道理】的视频 $DY_SHORT 复制此链接，打开抖音搜索，直接观看视频！"
        val r = MediaExtractor.extract(text)
        assertEquals("douyin", r.platform)
        assertTrue(r.media.isNotEmpty())
    }

    @Test fun liveXiaohongshuBestEffort() {
        online()
        // 数据中心 IP 常被小红书风控；能解析出媒体则校验可下载，否则记为已知限制（不虚报成功）
        val url = "http://xhslink.com/o/8G529qPH5ZL"
        try {
            val logs = ArrayList<String>()
            val r = MediaExtractor.extract(url) { logs += it }
            println("xhs OK source=${r.source} media=${r.media} logs=$logs")
            assertEquals("xiaohongshu", r.platform)
            assertTrue(r.media.isNotEmpty())
            val first = r.media.first()
            assertDownloadable(first.url, expectVideo = first.type != "image")
        } catch (e: ExtractException) {
            println("xhs BLOCKED (expected from datacenter): ${e.message}")
            Assume.assumeTrue(
                "Xiaohongshu live parse blocked from this environment: ${e.message}",
                false
            )
        }
    }


    @Test fun recognizesXhslinkCnInShareText() {
        val share = "一起游泳吗❓ 最近台风天阴雨绵绵🌧️ 感觉雨天更喜欢... https://xhslink.cn/o/6SwAKn9GzuT 复制口令，直达【小红书】阅读好文~"
        assertEquals(MediaExtractor.Platform.XIAOHONGSHU, MediaExtractor.detectPlatform(share))
        assertEquals("https://xhslink.cn/o/6SwAKn9GzuT", MediaExtractor.findXhsUrl(share))
        assertTrue(MediaExtractor.isXhsShortLink("https://xhslink.cn/o/6SwAKn9GzuT"))
        assertTrue(MediaExtractor.isXhsShortLink("http://xhslink.com/o/abc"))
    }

    @Test fun describeBugpkXhslinkCn400() {
        val json = """{"code":400,"msg":"Unsupported note url host","data":[]}"""
        val msg = MediaExtractor.describeApiFailure(json, "bugpk-xhs")!!
        assertTrue(msg, msg.contains("400"))
        assertTrue(msg, msg.contains("xhslink.cn") || msg.contains("短链域名"))
        assertNull(MediaExtractor.parseHk0Style(json, "xiaohongshu", "bugpk"))
    }

    @Test fun mujieStyleImageAlbum() {
        val json = """{"code":200,"msg":"获取成功","data":{"src":"https://xhslink.cn/o/x","author":"Xxxxy-。","type":"图文","url":null,
          "images":["http://sns-webpic-qc.xhscdn.com/a.jpg","http://sns-webpic-qc.xhscdn.com/b.jpg"]}}"""
        val r = MediaExtractor.parseHk0Style(json, "xiaohongshu", "mujie")!!
        assertEquals(2, r.media.size)
        assertTrue(r.media[0].url.startsWith("https://"))
        assertEquals("Xxxxy-。", r.author)
        assertEquals("image", r.media[0].type)
    }

    @Test fun preferHttpsHelper() {
        assertEquals("https://a.com/x", MediaExtractor.preferHttps("http://a.com/x"))
        assertEquals("https://a.com/x", MediaExtractor.preferHttps("https://a.com/x"))
    }

    @Test fun liveXiaohongshuXhslinkCnShare() {
        online()
        val share = "一起游泳吗❓ 最近台风天阴雨绵绵🌧️ 感觉雨天更喜欢... https://xhslink.cn/o/6SwAKn9GzuT 复制口令，直达【小红书】阅读好文~"
        val logs = ArrayList<String>()
        val r = MediaExtractor.extract(share) { logs += it }
        println("xhs.cn live: source=${r.source} id=${r.id} author=${r.author} n=${r.media.size} logs=$logs")
        assertEquals("xiaohongshu", r.platform)
        assertTrue("expected images, got ${r.media}", r.media.size >= 2)
        assertTrue(r.media.all { it.type == "image" })
        assertTrue(r.media.all { it.url.startsWith("https://") })
        assertDownloadable(r.media.first().url, expectVideo = false)
    }

    @Test fun resolveXhsStopsBeforeCaptcha() {
        online()
        val page = MediaExtractor.resolveXhsToNotePage("https://xhslink.cn/o/6SwAKn9GzuT")
        println("resolved page=$page")
        assertNotNull(MediaExtractor.parseXhsNoteId(page))
        assertFalse(page.contains("captcha"))
        assertTrue(page.contains("xiaohongshu.com"))
    }

    @Test fun invalidLinkMessage() {
        try {
            MediaExtractor.extract("hello world"); fail()
        } catch (e: ExtractException) {
            assertTrue(e.message!!.contains("无法识别") || e.message!!.contains("链接无效"))
        }
    }
}

/** 保留旧测试类名的薄包装，避免遗漏 */
class TweetExtractorTest {
    private val inner = MediaExtractorTest()
    @Test fun parsesManyLinkForms() = inner.parsesTwitterLinkForms()
    @Test fun tokenMatchesReferenceImplementation() = inner.tokenMatchesReference()
}
