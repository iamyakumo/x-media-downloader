package com.yakumo.xvideo

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var btnDownload: Button
    private lateinit var loading: ProgressBar
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var sessionHeader: View
    private lateinit var sessionHint: TextView
    private lateinit var sessionList: LinearLayout
    private lateinit var btnClearSession: Button
    private lateinit var btnLang: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val dm by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    private val tracking = LinkedHashMap<Long, Pair<ProgressBar, TextView>>()
    private val names = HashMap<Long, String>()
    private val session = ArrayList<SessionItem>()
    private var pendingStart = false

    data class SessionItem(
        val downloadId: Long,
        val fileName: String,
        val mime: String,
        val kindKey: String, // image|video|gif
        var status: Int = DownloadManager.STATUS_PENDING,
        var bytes: Long = 0,
        var localUri: String = "",
        var reason: Int = 0,
    )

    private val completeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (session.none { it.downloadId == id } && !names.containsKey(id)) return
            refreshProgress()
            renderSessionList()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            val busy = tracking.isNotEmpty() || session.any {
                it.status == DownloadManager.STATUS_RUNNING ||
                    it.status == DownloadManager.STATUS_PENDING ||
                    it.status == DownloadManager.STATUS_PAUSED
            }
            if (busy) {
                refreshProgress()
                renderSessionList()
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindClicks()
        updateLangChip()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(completeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completeReceiver, filter)
        }

        restoreSession()
        refreshProgress()
        renderSessionList()
        handleShareIntent(intent)
    }

    private fun bindViews() {
        input = findViewById(R.id.input)
        btnDownload = findViewById(R.id.btnDownload)
        loading = findViewById(R.id.loading)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        sessionHeader = findViewById(R.id.sessionHeader)
        sessionHint = findViewById(R.id.sessionHint)
        sessionList = findViewById(R.id.sessionList)
        btnClearSession = findViewById(R.id.btnClearSession)
        btnLang = findViewById(R.id.btnLang)
    }

    private fun bindClicks() {
        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            input.setText(""); status.text = ""; results.removeAllViews()
        }
        btnClearSession.setOnClickListener { clearSessionList(showToast = true) }
        btnDownload.setOnClickListener { startExtract() }
        btnLang.setOnClickListener { onLangToggle() }
    }

    /** Chip shows the language you can switch TO: EN when UI is Chinese, 文 when English. */
    private fun updateLangChip() {
        btnLang.text = if (LocaleHelper.effectiveIsChinese(this)) "EN" else "文"
        btnLang.contentDescription = if (LocaleHelper.effectiveIsChinese(this))
            "Switch to English" else "切换到中文"
    }

    private fun onLangToggle() {
        LocaleHelper.toggle(this)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        refreshProgress()
        renderSessionList()
        if (session.any {
                it.status == DownloadManager.STATUS_RUNNING ||
                    it.status == DownloadManager.STATUS_PENDING ||
                    it.status == DownloadManager.STATUS_PAUSED
            }
        ) {
            handler.removeCallbacks(poller)
            handler.post(poller)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(completeReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.setText(text.trim())
            startExtract()
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) toast(getString(R.string.toast_clipboard_empty))
        else {
            input.setText(text.trim())
            input.setSelection(input.text.length)
        }
    }

    private fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && pendingStart) {
            pendingStart = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startExtract()
            else showError(getString(R.string.toast_need_storage))
        }
    }

    private fun startExtract() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { showError(getString(R.string.toast_need_link)); return }
        if (needsStoragePermission()) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            return
        }
        clearSessionList(showToast = false)
        results.removeAllViews()
        tracking.clear()
        names.clear()

        btnDownload.isEnabled = false
        loading.visibility = View.VISIBLE
        status.setTextColor(getColor(R.color.text_secondary))
        status.text = getString(R.string.status_parsing)
        Thread {
            try {
                val r = MediaExtractor.extract(text)
                handler.post { onExtracted(r) }
            } catch (e: ExtractException) {
                handler.post { showError(ErrorText.tr(this, e.message ?: getString(R.string.parse_fail, ""))) }
            } catch (e: Exception) {
                handler.post { showError(getString(R.string.parse_fail, e.message ?: "")) }
            }
        }.start()
    }

    private fun showError(msg: String) {
        btnDownload.isEnabled = true
        loading.visibility = View.GONE
        status.setTextColor(getColor(R.color.danger))
        status.text = "❌ $msg"
        toast(msg)
    }

    private fun platformLabel(p: String) = when (p) {
        "twitter" -> getString(R.string.platform_x)
        "douyin" -> getString(R.string.platform_douyin)
        "xiaohongshu" -> getString(R.string.platform_xhs)
        else -> p
    }

    private fun kindLabel(type: String) = when (type) {
        "image" -> getString(R.string.kind_image)
        "gif" -> getString(R.string.kind_gif)
        else -> getString(R.string.kind_video)
    }

    private fun onExtracted(r: ExtractResult) {
        btnDownload.isEnabled = true
        loading.visibility = View.GONE
        status.setTextColor(getColor(R.color.success))
        val who = if (r.author.isNotBlank()) " · ${r.author}" else ""
        val vids = r.media.count { it.type != "image" }
        val imgs = r.media.count { it.type == "image" }
        val summary = buildString {
            append("✅ ${platformLabel(r.platform)}$who：")
            if (vids > 0) append(getString(R.string.summary_videos, vids))
            if (vids > 0 && imgs > 0) append(getString(R.string.summary_plus))
            if (imgs > 0) append(getString(R.string.summary_images, imgs))
            append(getString(R.string.status_start_download))
            append('\n')
            append(r.text.take(80))
        }
        status.text = summary
        r.media.forEachIndexed { i, m -> enqueue(r, m, i) }
        persistSession()
        renderSessionList()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    private fun enqueue(r: ExtractResult, m: MediaItem, index: Int) {
        val author = r.author.replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fff]"), "").ifBlank { r.platform }
        val safeAuthor = author.take(24).ifBlank { r.platform }
        val idPart = r.id.ifBlank { "item" }.take(24)
        val suffix = if (r.media.size > 1) "_${index + 1}" else ""
        val ext = m.fileExt()
        val fileName = "${r.platform}_${safeAuthor}_${idPart}$suffix.$ext"
        val quality = if (m.height > 0) " ${m.width}x${m.height}" else ""
        val kind = kindLabel(m.type)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            text = "${index + 1}. $kind$quality  →  $fileName\n${getString(R.string.progress_waiting)}"
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        card.addView(label); card.addView(bar)
        results.addView(card, 0)

        try {
            val dlUrl = if (m.url.startsWith("http://")) "https://" + m.url.substring(7) else m.url
            val req = DownloadManager.Request(Uri.parse(dlUrl))
                .setTitle(fileName)
                .setDescription(getString(R.string.dm_description))
                .setMimeType(m.mimeType())
                .addRequestHeader("User-Agent", UA_DL)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MediaDownloads/$fileName")
            when (r.platform) {
                "xiaohongshu" -> req.addRequestHeader("Referer", "https://www.xiaohongshu.com/")
                "douyin" -> req.addRequestHeader("Referer", "https://www.douyin.com/")
                "twitter" -> req.addRequestHeader("Referer", "https://x.com/")
            }
            @Suppress("DEPRECATION")
            req.allowScanningByMediaScanner()
            val id = dm.enqueue(req)
            tracking[id] = bar to label
            names[id] = "${index + 1}. $kind$quality  →  $fileName"
            session.add(
                SessionItem(
                    downloadId = id,
                    fileName = fileName,
                    mime = m.mimeType(),
                    kindKey = m.type,
                    status = DownloadManager.STATUS_PENDING,
                )
            )
        } catch (e: Exception) {
            bar.isIndeterminate = false
            label.setTextColor(getColor(R.color.danger))
            label.text = "${index + 1}. $kind  ${getString(R.string.progress_start_fail, e.message ?: "")}"
        }
    }

    private fun refreshProgress() {
        val ids = (tracking.keys + session.map { it.downloadId }).toSet().toLongArray()
        if (ids.isEmpty()) return
        val done = ArrayList<Long>()
        dm.query(DownloadManager.Query().setFilterById(*ids))?.use { c ->
            val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iSoFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val st = c.getInt(iStatus)
                val so = c.getLong(iSoFar)
                val total = c.getLong(iTotal)
                val reason = c.getInt(iReason)
                val local = if (iLocal >= 0) c.getString(iLocal) ?: "" else ""

                session.find { it.downloadId == id }?.let { item ->
                    item.status = st
                    item.bytes = so
                    item.reason = reason
                    if (local.isNotBlank()) item.localUri = local
                }

                val (bar, label) = tracking[id] ?: continue
                val name = names[id] ?: ""
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        bar.isIndeterminate = false; bar.progress = 100
                        label.setTextColor(getColor(R.color.success))
                        label.text = "$name\n✅ ${getString(R.string.progress_done, mb(so))}"
                        done += id
                    }
                    DownloadManager.STATUS_FAILED -> {
                        bar.isIndeterminate = false
                        label.setTextColor(getColor(R.color.danger))
                        label.text = "$name\n❌ ${getString(R.string.progress_fail, reason)}"
                        done += id
                    }
                    DownloadManager.STATUS_PAUSED ->
                        label.text = "$name\n⏸ ${getString(R.string.progress_paused, mb(so))}"
                    DownloadManager.STATUS_PENDING ->
                        label.text = "$name\n${getString(R.string.progress_waiting)}"
                    else -> {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (so * 100 / total).toInt()
                            label.text = "$name\n⬇️ ${getString(R.string.progress_downloading, bar.progress, mb(so), mb(total))}"
                        } else {
                            label.text = "$name\n⬇️ ${getString(R.string.progress_downloading_bytes, mb(so))}"
                        }
                    }
                }
            }
        }
        val newlyDone = done.filter { names.containsKey(it) }
        newlyDone.forEach { id ->
            val item = session.find { it.downloadId == id }
            when (item?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> toast(getString(R.string.toast_dl_ok, item.fileName))
                DownloadManager.STATUS_FAILED -> toast(getString(R.string.toast_dl_fail, item.fileName))
            }
            tracking.remove(id); names.remove(id)
        }
        if (tracking.isEmpty() && newlyDone.isNotEmpty()) {
            status.text = status.text.toString()
                .replace(getString(R.string.status_start_download), " · ${getString(R.string.status_done)}")
                .replace("，开始下载…", " · ${getString(R.string.status_done)}")
                .replace(", downloading…", " · ${getString(R.string.status_done)}")
        }
        persistSession()
    }

    private fun clearSessionList(showToast: Boolean) {
        session.clear()
        sessionList.removeAllViews()
        sessionHeader.visibility = View.GONE
        sessionHint.visibility = View.GONE
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_SESSION).apply()
        if (showToast) toast(getString(R.string.toast_session_cleared))
    }

    private fun renderSessionList() {
        sessionList.removeAllViews()
        if (session.isEmpty()) {
            sessionHeader.visibility = View.GONE
            sessionHint.visibility = View.GONE
            return
        }
        sessionHeader.visibility = View.VISIBLE
        sessionHint.visibility = View.VISIBLE

        session.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_session_row)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }
            val icon = TextView(this).apply {
                textSize = 22f
                text = when (item.kindKey) {
                    "image" -> "🖼️"
                    "gif" -> "🎞️"
                    else -> "🎬"
                }
                setPadding(0, 0, dp(12), 0)
            }
            val info = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setLineSpacing(dp(2).toFloat(), 1f)
                text = buildString {
                    append(item.fileName)
                    append('\n')
                    append(
                        when (item.status) {
                            DownloadManager.STATUS_SUCCESSFUL ->
                                "✅ ${getString(R.string.session_done, mb(item.bytes))}"
                            DownloadManager.STATUS_FAILED ->
                                "❌ ${getString(R.string.session_fail, item.reason)}"
                            DownloadManager.STATUS_RUNNING ->
                                "⬇️ ${getString(R.string.session_running, mb(item.bytes))}"
                            DownloadManager.STATUS_PAUSED ->
                                "⏸ ${getString(R.string.session_paused)}"
                            else -> getString(R.string.session_pending)
                        }
                    )
                }
            }
            row.addView(icon)
            row.addView(info)
            row.setOnClickListener { openSessionItem(item) }
            sessionList.addView(row)
        }
    }

    private fun openSessionItem(item: SessionItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            toast(
                when (item.status) {
                    DownloadManager.STATUS_FAILED -> getString(R.string.toast_open_failed_item)
                    else -> getString(R.string.toast_open_pending)
                }
            )
            return
        }
        try {
            val uri = resolveOpenUri(item) ?: run {
                toast(getString(R.string.toast_file_missing))
                return
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.clipData = android.content.ClipData.newUri(contentResolver, item.fileName, uri)
            startActivity(Intent.createChooser(view, getString(R.string.chooser_open_with)))
        } catch (e: Exception) {
            toast(getString(R.string.toast_open_error, e.message ?: ""))
        }
    }

    private fun resolveOpenUri(item: SessionItem): Uri? {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MediaDownloads/${item.fileName}"
        )
        if (file.exists() && file.canRead()) {
            return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }
        try {
            val u = dm.getUriForDownloadedFile(item.downloadId)
            if (u != null) return u
        } catch (_: Exception) {}
        if (item.localUri.isNotBlank()) {
            val parsed = Uri.parse(item.localUri)
            if (parsed.scheme == "file") {
                val f = File(parsed.path ?: return null)
                if (f.exists()) return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            }
            return parsed
        }
        return null
    }

    private fun persistSession() {
        val arr = JSONArray()
        for (it in session) {
            arr.put(
                JSONObject()
                    .put("id", it.downloadId)
                    .put("fileName", it.fileName)
                    .put("mime", it.mime)
                    .put("kindKey", it.kindKey)
                    .put("status", it.status)
                    .put("bytes", it.bytes)
                    .put("localUri", it.localUri)
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SESSION, arr.toString()).apply()
    }

    private fun restoreSession() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SESSION, null) ?: return
        try {
            val arr = JSONArray(raw)
            session.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                session.add(
                    SessionItem(
                        downloadId = o.getLong("id"),
                        fileName = o.getString("fileName"),
                        mime = o.optString("mime", "application/octet-stream"),
                        kindKey = o.optString("kindKey", o.optString("kind", "video")).let {
                            when (it) {
                                "图片", "Image" -> "image"
                                "GIF" -> "gif"
                                "视频", "Video" -> "video"
                                else -> it
                            }
                        },
                        status = o.optInt("status", DownloadManager.STATUS_PENDING),
                        bytes = o.optLong("bytes", 0),
                        localUri = o.optString("localUri", ""),
                    )
                )
            }
        } catch (_: Exception) {
            session.clear()
        }
    }

    private fun mb(b: Long) = String.format("%.1f MB", b / 1048576.0)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PREFS = "xvideo_session"
        private const val KEY_SESSION = "session_items"
        private const val UA_DL =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MediaDownloader/1.4"
    }
}
