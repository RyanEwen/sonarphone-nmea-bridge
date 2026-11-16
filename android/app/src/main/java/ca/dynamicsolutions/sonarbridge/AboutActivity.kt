package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as MR

/**
 * The About screen (BRAND-8), opened from the last row of Settings: product name,
 * version and tagline, then Updates, then Links. There is no Diagnostics section
 * because the app has no log-sharing action (LOG-8).
 */
class AboutActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Same language handling as MainActivity, so API 29-32 shows the chosen language.
        super.attachBaseContext(AppLanguage.context(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        // Header: product name, version, and the store tagline.
        column.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                setTextAppearance(MR.style.TextAppearance_Material3_HeadlineSmall)
            },
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
                setTextAppearance(MR.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, dp(4), 0, 0)
            },
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.about_tagline)
                setTextAppearance(MR.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, dp(8), 0, 0)
            },
        )

        // Updates: neither flavor updates itself, so this is the download guidance, not a check.
        column.addView(ListRow.heading(this, getString(R.string.updates_heading), topPadDp = 24))
        column.addView(AndroidDownloads.buildGuidanceView(this, migration = !BuildConfig.IS_PLAY))

        // Links, in BRAND-8 order.
        column.addView(ListRow.heading(this, getString(R.string.about_links), topPadDp = 24))
        column.addView(ListRow.create(this, getString(R.string.about_source_code)) { AppLinks.open(this, AppLinks.REPOSITORY) })
        column.addView(ListRow.create(this, getString(R.string.about_privacy_policy)) { AppLinks.open(this, AppLinks.PRIVACY_POLICY) })
        column.addView(ListRow.create(this, getString(R.string.about_support)) { AppLinks.open(this, AppLinks.ISSUES) })
        column.addView(ListRow.create(this, getString(R.string.about_license)) { AppLinks.open(this, AppLinks.LICENSE) })
        column.addView(ListRow.create(this, getString(R.string.about_third_party_notices)) { showThirdPartyNotices() })

        val root = ScrollView(this).apply { addView(column) }
        // Edge-to-edge is enforced at targetSdk 35+; keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    /** Show the build-generated notices file (third_party_notices.txt) in a scrolling dialog. */
    private fun showThirdPartyNotices() {
        val text = assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
        val padding = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_third_party_notices))
            .setView(
                ScrollView(this).apply {
                    setPadding(padding, padding / 2, padding, 0)
                    addView(
                        TextView(this@AboutActivity).apply {
                            this.text = text
                            setTextAppearance(MR.style.TextAppearance_Material3_BodySmall)
                            setTextIsSelectable(true)
                        },
                    )
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        /** Written by the generate<Variant>ThirdPartyNotices Gradle task. */
        const val NOTICES_ASSET = "third_party_notices.txt"
    }
}
