package ca.dynamicsolutions.sonarbridge

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The passphrase is encrypted at rest, older plain-text copies migrate once, and backup excludes its file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class SonarPassphraseTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val cfg get() = app.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE)
    private val own get() = app.getSharedPreferences(SonarPassphrase.FILE, Context.MODE_PRIVATE)

    @Before
    fun reset() {
        TestKeys.install()
        cfg.edit().clear().commit()
        own.edit().clear().commit()
    }

    @Test
    fun savedPassphraseIsEncryptedAtRest() {
        SonarPassphrase.set(app, "boat-secret")

        assertEquals("boat-secret", SonarPassphrase.get(app))
        assertFalse(own.contains("pass"))
        val stored = own.getString("pass_enc", null)!!
        assertFalse(stored.contains("boat-secret"))
        // A fresh IV each time: the same value never encrypts to the same text.
        SonarPassphrase.set(app, "boat-secret")
        assertNotEquals(stored, own.getString("pass_enc", null))
    }

    @Test
    fun plainTextInTheSharedSettingsFileMigratesOnceAndLeavesOtherSettings() {
        cfg.edit().putString("pass", "legacy-secret").putString("ssid", "kept").commit()

        SonarPassphrase.migrate(app)
        assertEquals("legacy-secret", SonarPassphrase.get(app))
        assertFalse(cfg.contains("pass"))
        assertFalse(own.contains("pass"))
        assertEquals("kept", cfg.getString("ssid", null))

        // A second run finds nothing to move and changes nothing.
        val encrypted = own.getString("pass_enc", null)
        SonarPassphrase.migrate(app)
        assertEquals(encrypted, own.getString("pass_enc", null))
    }

    @Test
    fun plainTextInTheCredentialsFileIsEncryptedOnFirstRun() {
        own.edit().putString("pass", "from-last-version").commit()

        SonarPassphrase.migrate(app)
        assertEquals("from-last-version", SonarPassphrase.get(app))
        assertFalse(own.contains("pass"))
    }

    @Test
    fun migrationNeverOverwritesANewerPassphrase() {
        SonarPassphrase.set(app, "current")
        cfg.edit().putString("pass", "stale").commit()

        SonarPassphrase.migrate(app)
        assertEquals("current", SonarPassphrase.get(app))
        assertFalse(cfg.contains("pass"))
    }

    @Test
    fun anUndecryptableValueFallsBackToTheFactoryDefault() {
        SonarPassphrase.set(app, "boat-secret")
        TestKeys.install() // a different key, as after a Keystore reset

        assertEquals(SonarPassphrase.FACTORY_DEFAULT, SonarPassphrase.get(app))
        assertFalse(own.contains("pass_enc"))
    }

    @Test
    fun factoryDefaultUntilTheUserSavesOne() {
        assertEquals(SonarPassphrase.FACTORY_DEFAULT, SonarPassphrase.get(app))
    }

    @Test
    fun bothBackupRuleFilesExcludeOnlyThePassphraseFile() {
        val expected = "${SonarPassphrase.FILE}.xml"
        // Android 11 and lower.
        assertEquals(listOf("full-backup-content" to expected), excludes(R.xml.backup_rules))
        // Android 12 and higher: cloud backup and device transfer separately.
        assertEquals(listOf("cloud-backup" to expected, "device-transfer" to expected), excludes(R.xml.data_extraction_rules))
    }

    /** (enclosing section, path) for every sharedpref exclude in a rules file. */
    private fun excludes(resource: Int): List<Pair<String, String>> {
        val parser = app.resources.getXml(resource)
        val sections = ArrayDeque<String>()
        val found = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "exclude") {
                        assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
                        found += sections.last() to parser.getAttributeValue(null, "path")
                    }
                    sections.addLast(parser.name)
                }

                XmlPullParser.END_TAG -> sections.removeLast()
            }
        }
        assertTrue(found.isNotEmpty())
        return found
    }
}
