package com.yakumo.xvideo

import android.content.Context

/** Best-effort English mapping for extractor Chinese errors. */
object ErrorText {
    private val MAP = listOf(
        "请先输入或粘贴链接" to "Please paste a link first",
        "链接无效：请粘贴 X / Twitter" to "Invalid link: paste an X / Twitter post URL",
        "链接无效：请粘贴抖音" to "Invalid link: paste a Douyin share link",
        "链接无效：请粘贴小红书" to "Invalid link: paste a Xiaohongshu share link (xhslink.com / xhslink.cn)",
        "无法从抖音链接中识别作品 ID" to "Could not find a Douyin post ID in this link",
        "无法识别链接" to "Unrecognized link: paste an X / Xiaohongshu / Douyin post URL or share text",
        "这条内容里没有可下载的视频或图片" to "No downloadable video or image in this post",
        "这条帖子里没有视频或 GIF" to "This post has no video or GIF",
        "网络连接失败" to "Network error — check your connection and try again",
        "无法获取该小红书笔记" to "Could not fetch this Xiaohongshu note (blocked, private, or expired short link)",
        "无法获取该内容" to "Could not fetch this content (deleted, private, or blocked)",
        "解析失败（服务返回" to "Parse failed (service returned",
        "不支持该短链域名" to "This short-link host is not supported",
        "作品不可访问或需要登录" to "Post unavailable or requires login",
        "解析服务请求过频或 IP 被限制" to "Parser rate-limited or IP blocked",
        "解析服务登录态失效" to "Parser session expired",
        "链接格式无效" to "Invalid link format",
        "也可在小红书 App 重新复制" to "Try copying the share link again from the Xiaohongshu app",
        "请用 App 分享的 xhslink" to "Use a full xhslink.com / xhslink.cn share link from the app",
        "需要存储权限" to "Storage permission is required to save to Downloads",
    )

    fun tr(ctx: Context, msg: String): String {
        if (LocaleHelper.effectiveIsChinese(ctx)) return msg
        var out = msg
        for ((zh, en) in MAP) {
            if (out.contains(zh)) out = out.replace(zh, en)
        }
        return out
    }
}
