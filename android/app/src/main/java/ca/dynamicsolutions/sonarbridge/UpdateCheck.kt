package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater (same pattern as the persistent app's UpdatePlugin): check
 * the GitHub latest release on cold start and on resume (throttled). "Update"
 * downloads the APK via the system DownloadManager and launches the package
 * installer when it completes — no browser round-trip. "Later" mutes that
 * version; an accepted-but-not-installed update prompts again next time.
 */
object UpdateCheck {
    private const val REPO = "RyanEwen/sonarphone-nmea-bridge"
    private const val APK_NAME = "sonarbridge-update.apk"
    private const val RESUME_THROTTLE_MS = 3 * 60 * 60 * 1000L
    private var checkedThisProcess = false

    /** Automatic check: throttled, and muted versions stay quiet. */
    fun maybeCheck(activity: Activity, scope: CoroutineScope) {
        // Play delivers updates; dev/debug builds shouldn't nag about releases.
        if (BuildConfig.IS_PLAY || BuildConfig.DEBUG) return
        val prefs = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (checkedThisProcess && now - prefs.getLong("upd_last_check", 0) < RESUME_THROTTLE_MS) {
            return
        }
        checkedThisProcess = true
        prefs.edit().putLong("upd_last_check", now).apply()
        runCheck(activity, scope, silent = true)
    }

    /** Settings-button check: always runs, always gives feedback. */
    fun manualCheck(activity: Activity, scope: CoroutineScope) {
        runCheck(activity, scope, silent = false)
    }

    /** Is release version `a` strictly newer than installed `b`? (semver-ish;
     *  suffixes like -dev/-pre are ignored, so 0.1.5-pre is not < 0.1.4) */
    private fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) =
            v.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until 3) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da > db
        }
        return false
    }

    private fun runCheck(activity: Activity, scope: CoroutineScope, silent: Boolean) {
        val prefs = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        scope.launch(Dispatchers.IO) {
            val rel = runCatching { fetchLatest() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                when {
                    rel == null -> if (!silent) {
                        Toast.makeText(activity, "Couldn't check for updates", Toast.LENGTH_LONG).show()
                    }
                    !isNewer(rel.version, BuildConfig.VERSION_NAME) -> if (!silent) {
                        Toast.makeText(
                            activity,
                            "Up to date (v${BuildConfig.VERSION_NAME})",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    silent && prefs.getString("upd_seen", "") == rel.version -> Unit // muted
                    else -> MaterialAlertDialogBuilder(activity)
                        .setTitle("Update available: ${rel.version}")
                        .setMessage(
                            rel.notes.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "").trim()
                        )
                        .setPositiveButton("Update") { _, _ ->
                            downloadAndInstall(activity, rel)
                        }
                        .setNegativeButton("Later") { _, _ ->
                            prefs.edit().putString("upd_seen", rel.version).apply()
                        }
                        .show()
                }
            }
        }
    }

    /** DownloadManager fetch -> package installer on completion. */
    private fun downloadAndInstall(activity: Activity, rel: Release) {
        val app = activity.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        File(app.getExternalFilesDir(null), APK_NAME).delete() // avoid -1 suffixed dupes
        val request = DownloadManager.Request(Uri.parse(rel.apkUrl))
            .setTitle("SonarBridge ${rel.version}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, null, APK_NAME)
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(request)
        Toast.makeText(app, "Downloading update…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { app.unregisterReceiver(this) }
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri != null) {
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                } else {
                    Toast.makeText(app, "Update download failed", Toast.LENGTH_LONG).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private class Release(val version: String, val notes: String, val apkUrl: String)

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    break
                }
            }
            return apkUrl?.let { Release(version, json.optString("body", ""), it) }
        } finally {
            conn.disconnect()
        }
    }
}
