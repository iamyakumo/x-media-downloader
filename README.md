# X媒体下载 / X Downloader

Android app that downloads videos and images from X (Twitter), Douyin, and Xiaohongshu share links.

- Paste or share a link → parse → download via Android DownloadManager to `Download/MediaDownloads/`
- Session list with open-in-system-viewer
- Chinese / English UI (follows system until you toggle)

## 网页版 / Web

iPhone 与浏览器版：<https://iamyakumo.github.io/x-media-downloader/>

用 Safari 打开后，通过「分享 → 添加到主屏幕」即可装到桌面。

## Build

Requires JDK 17+, Android SDK (compileSdk 34).

```bash
./gradlew assembleRelease
```

Release signing uses optional local `keystore/xvideo-release.jks` and env vars `XVIDEO_STORE_PASSWORD` / `XVIDEO_KEY_PASSWORD`. Debug builds work without them.

## Notes

Media extraction relies on public third-party endpoints and may break if those services change. Private / deleted / login-gated posts are unsupported.
