package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as MR

/** Download choices and migration guidance shared by the launch notice and Settings. */
object AndroidDownloads {
    private const val NOTICE_SEEN = "play_migration_notice_seen"
    private const val GITHUB_URL = "https://github.com/RyanEwen/sonarphone-nmea-bridge/releases"
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=ca.dynamicsolutions.sonarbridge"

    /** Legacy release installations see this once; dismissing never changes sonar settings. */
    fun maybeShowMigrationNotice(activity: Activity) {
        if (BuildConfig.IS_PLAY || BuildConfig.DEBUG) return
        val prefs = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (prefs.getBoolean(NOTICE_SEEN, false)) return

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.download_change))
            .setView(android.widget.ScrollView(activity).apply {
                setPadding(padding, 0, padding, 0)
                addView(buildGuidanceView(activity, migration = true))
            })
            .setNegativeButton(activity.getString(R.string.later)) { _, _ -> }
            .setOnDismissListener {
                prefs.edit().putBoolean(NOTICE_SEEN, true).apply()
            }
            .show()
    }

    /** Keep both download choices equally styled, with persistent guidance below Settings. */
    fun buildGuidanceView(activity: Activity, migration: Boolean): android.view.View {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(TextView(activity).apply {
            // Match the existing Settings explanatory-note appearance.
            setTextAppearance(MR.style.TextAppearance_Material3_BodySmall)
            alpha = 0.7f
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            text = if (migration) activity.getString(R.string.migration_guidance) else
                activity.getString(R.string.download_guidance, BuildConfig.VERSION_NAME)
        })
        addDownloadButton(activity, content, activity.getString(R.string.download_github), GITHUB_URL)
        addDownloadButton(activity, content, activity.getString(R.string.download_play), PLAY_URL)
        return content
    }

    /** Open the user's chosen download page; handle devices without a URL handler. */
    private fun addDownloadButton(activity: Activity, parent: LinearLayout, label: String, url: String) {
        parent.addView(MaterialButton(activity, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(activity, activity.getString(R.string.download_no_handler), Toast.LENGTH_LONG).show()
                }
            }
        })
    }
}
