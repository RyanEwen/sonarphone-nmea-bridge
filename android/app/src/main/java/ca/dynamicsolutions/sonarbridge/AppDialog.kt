package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.view.View
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The app's one dialog shape: a Material alert dialog with a title and either scrolling content or a single choice. */
object AppDialog {
    /** A titled dialog whose [content] scrolls, with the app's standard side padding. Add buttons, then show(). */
    fun scrolling(activity: Activity, title: String, content: View): MaterialAlertDialogBuilder {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        return MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(
                ScrollView(activity).apply {
                    setPadding(padding, padding / 2, padding, 0)
                    addView(content)
                },
            )
    }

    /**
     * A titled single-choice list with a Cancel button. Picking an item closes the dialog
     * and calls [onChoose] with its index.
     */
    fun choice(activity: Activity, title: String, labels: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                onChoose(index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
