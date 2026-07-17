package com.rewen.sonarbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update prompt (same pattern as the persistent app): check the GitHub
 * latest release on cold start and on resume (throttled), and offer the APK
 * when a version we haven't seen/dismissed is out. "Update" opens the asset
 * URL — Android's installer takes it from there.
 */
object UpdateCheck {
    private const val REPO = "RyanEwen/sonarphone-nmea-bridge"
    private const val RESUME_THROTTLE_MS = 3 * 60 * 60 * 1000L
    private var checkedThisProcess = false

    fun maybeCheck(activity: Activity, scope: CoroutineScope) {
        val prefs = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (checkedThisProcess && now - prefs.getLong("upd_last_check", 0) < RESUME_THROTTLE_MS) {
            return
        }
        checkedThisProcess = true
        prefs.edit().putLong("upd_last_check", now).apply()

        scope.launch(Dispatchers.IO) {
            val rel = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            val current = BuildConfig.VERSION_NAME
            if (rel.version == current) return@launch
            if (prefs.getString("upd_seen", "") == rel.version) return@launch
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Update available: ${rel.version}")
                    .setMessage(rel.notes.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "").trim())
                    .setPositiveButton("Update") { _, _ ->
                        prefs.edit().putString("upd_seen", rel.version).apply()
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl)))
                    }
                    .setNegativeButton("Later") { _, _ ->
                        prefs.edit().putString("upd_seen", rel.version).apply()
                    }
                    .show()
            }
        }
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
