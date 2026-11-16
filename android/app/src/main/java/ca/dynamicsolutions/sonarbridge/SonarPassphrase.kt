package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * The T-Box Wi-Fi passphrase. It lives in its own preferences file so the backup
 * rules (res/xml/backup_rules.xml and data_extraction_rules.xml) can exclude it
 * while every other setting is still backed up and transferred (SEC-2).
 */
object SonarPassphrase {
    /** Preferences file name; the backup rules exclude `credentials.xml`. */
    const val FILE = "credentials"

    /** The Vexilar T-Box factory default, used until the user enters another. */
    const val FACTORY_DEFAULT = "12345678"

    private const val KEY = "pass"

    /** The stored passphrase, or the factory default when none was saved. */
    fun get(context: Context): String = own(context).getString(KEY, FACTORY_DEFAULT) ?: FACTORY_DEFAULT

    /** Save the passphrase the user entered. */
    fun set(context: Context, value: String) {
        own(context).edit().putString(KEY, value).apply()
    }

    /**
     * Move a passphrase that older versions kept in the shared "cfg" file into this
     * file, then remove it from "cfg". Safe to run on every start: a value already in
     * this file is never overwritten, and a second run finds nothing to move.
     */
    fun migrate(context: Context) {
        val cfg = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!cfg.contains(KEY)) return

        val own = own(context)
        val legacy = cfg.getString(KEY, null)
        if (!own.contains(KEY) && legacy != null) {
            own.edit().putString(KEY, legacy).commit()
        }
        cfg.edit().remove(KEY).commit()
    }

    private fun own(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
