package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Every web address the app opens, in one place, so a repository move edits one file. */
object AppLinks {
    const val REPOSITORY = "https://github.com/Dynamic-Solutions-Canada/sonarbridge"
    const val ISSUES = "$REPOSITORY/issues"
    const val LICENSE = "$REPOSITORY/blob/main/LICENSE"
    const val PRIVACY_POLICY = "https://www.dynamic-solutions.ca/privacy/sonarbridge"
    const val PLAY = "https://play.google.com/store/apps/details?id=ca.dynamicsolutions.sonarbridge"

    /** Open [url] in the user's browser; explain instead of crashing when nothing can open it. */
    fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.link_no_handler), Toast.LENGTH_LONG).show()
        }
    }
}
