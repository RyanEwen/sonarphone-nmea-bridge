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

    private const val MIGRATION_GUIDANCE =
        "You can still download and install the APK manually from GitHub. " +
        "New downloads from GitHub and Google Play will now offer the same app.\n\n" +
        "Android cannot install the new app over this old GitHub app because their signing keys differ. " +
        "Wait until GitHub offers an APK whose name ends in -play.apk, or use Google Play if it is available to you.\n\n" +
        "Before uninstalling, record your settings, including Wi-Fi name and password, units, sonar controls, " +
        "calibration offsets and shallow-water alarm. Save any raw frame logs you want to keep. " +
        "Disconnect the bridge, uninstall this old app, then install the new app from either place. " +
        "Re-enter your settings, grant the requested permissions and reconnect. " +
        "Uninstalling clears local settings and app files; they do not transfer automatically.\n\n" +
        "Future APKs from GitHub update the new app normally. It does not download APK updates itself."

    /** Legacy release installations see this once; dismissing never changes sonar settings. */
    fun maybeShowMigrationNotice(activity: Activity) {
        if (BuildConfig.IS_PLAY || BuildConfig.DEBUG) return
        val prefs = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (prefs.getBoolean(NOTICE_SEEN, false)) return

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(activity)
            .setTitle("A change to Android downloads")
            .setView(android.widget.ScrollView(activity).apply {
                setPadding(padding, 0, padding, 0)
                addView(buildGuidanceView(activity, migration = true))
            })
            .setNegativeButton("Later") { _, _ -> }
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
            text = if (migration) MIGRATION_GUIDANCE else
                "Version ${BuildConfig.VERSION_NAME}. GitHub and Google Play offer the same app. " +
                "Download future APKs manually from GitHub, or use Google Play for updates. " +
                "The app does not download APK updates itself."
        })
        addDownloadButton(activity, content, "Download from GitHub", GITHUB_URL)
        addDownloadButton(activity, content, "Get it on Google Play", PLAY_URL)
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
                    Toast.makeText(activity, "No app can open this download page", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
}
