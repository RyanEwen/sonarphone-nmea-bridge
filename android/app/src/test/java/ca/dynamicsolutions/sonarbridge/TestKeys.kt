package ca.dynamicsolutions.sonarbridge

import javax.crypto.KeyGenerator

/** Robolectric has no Android Keystore, so tests encrypt the passphrase with an in-memory AES key. */
object TestKeys {
    /** Install a fresh software key; returns it so a test can later swap in a different one. */
    fun install(): SonarPassphrase.KeySource {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val source = SonarPassphrase.KeySource { key }
        SonarPassphrase.keySource = source
        return source
    }
}
