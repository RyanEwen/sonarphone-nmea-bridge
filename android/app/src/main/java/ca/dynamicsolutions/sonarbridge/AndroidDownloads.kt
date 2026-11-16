package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MR

/** Download choices and migration guidance shared by the launch notice and Settings. */
object AndroidDownloads {

    /** Legacy release installations see this once; dismissing never changes sonar settings. */
    fun maybeShowMigrationNotice(activity: Activity) {
        if (BuildConfig.IS_PLAY || BuildConfig.DEBUG) return
        val settings = AppSettings(activity)
        if (settings.migrationNoticeSeen) return

        AppDialog.scrolling(activity, activity.getString(R.string.download_change), buildGuidanceView(activity, migration = true))
            .setNegativeButton(activity.getString(R.string.later)) { _, _ -> }
            .setOnDismissListener {
                settings.migrationNoticeSeen = true
            }
            .show()
    }

    /** Keep both download choices equally styled, with persistent guidance below Settings. */
    fun buildGuidanceView(activity: Activity, migration: Boolean): android.view.View {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            TextView(activity).apply {
                // Match the existing Settings explanatory-note appearance.
                setTextAppearance(MR.style.TextAppearance_Material3_BodySmall)
                alpha = 0.7f
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
                text = if (migration) {
                    activity.getString(R.string.migration_guidance)
                } else {
                    activity.getString(R.string.download_guidance, BuildConfig.VERSION_NAME)
                }
            },
        )
        addDownloadButton(activity, content, activity.getString(R.string.download_github), AppLinks.RELEASES)
        addDownloadButton(activity, content, activity.getString(R.string.download_play), AppLinks.PLAY)
        return content
    }

    /** A button that opens the user's chosen download page. */
    private fun addDownloadButton(activity: Activity, parent: LinearLayout, label: String, url: String) {
        parent.addView(
            MaterialButton(activity, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = label
                setOnClickListener { AppLinks.open(activity, url) }
            },
        )
    }
}
