package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The T-Box Wi-Fi passphrase (SEC-2). It is stored encrypted with AES/GCM under a key
 * held by the Android Keystore, in its own preferences file that the backup rules
 * (res/xml/backup_rules.xml and data_extraction_rules.xml) exclude, while every other
 * setting is still backed up and transferred.
 */
object SonarPassphrase {
    /** Preferences file name; the backup rules exclude `credentials.xml`. */
    const val FILE = "credentials"

    /** The Vexilar T-Box factory default, used until the user enters another. */
    const val FACTORY_DEFAULT = "12345678"

    /** Base64 of the 12-byte GCM IV followed by the ciphertext and tag. */
    private const val KEY_ENCRYPTED = "pass_enc"

    /** Plain-text value written by versions before encryption; migrated on first run. */
    private const val KEY_PLAIN = "pass"

    private const val TAG = "SonarPassphrase"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Where the encryption key comes from. Tests replace it, because Robolectric has no Android Keystore. */
    fun interface KeySource {
        fun key(): SecretKey
    }

    /** The app's AES key in the Android Keystore, created on first use; it never leaves the device. */
    private val androidKeystore = KeySource {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("sonar_passphrase", null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        "sonar_passphrase",
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    /** The key source in use; replaced only by tests. */
    @Volatile
    internal var keySource: KeySource = androidKeystore

    /**
     * The stored passphrase, or the factory default when none was saved. If the stored
     * value can no longer be decrypted (for example the Keystore key was reset), it is
     * discarded and the factory default is returned, so the user re-enters it.
     */
    fun get(context: Context): String {
        val own = own(context)
        val encrypted = own.getString(KEY_ENCRYPTED, null) ?: return FACTORY_DEFAULT
        return try {
            decrypt(encrypted)
        } catch (error: GeneralSecurityException) {
            Log.w(TAG, "stored passphrase could not be decrypted; using the factory default", error)
            own.edit().remove(KEY_ENCRYPTED).apply()
            FACTORY_DEFAULT
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "stored passphrase is malformed; using the factory default", error)
            own.edit().remove(KEY_ENCRYPTED).apply()
            FACTORY_DEFAULT
        }
    }

    /**
     * Encrypt and save the passphrase the user entered. If encryption is unavailable the
     * previous value is kept and the failure is logged; a passphrase is never written in plain text.
     */
    fun set(context: Context, value: String) {
        try {
            own(context).edit().putString(KEY_ENCRYPTED, encrypt(value)).remove(KEY_PLAIN).apply()
        } catch (error: GeneralSecurityException) {
            Log.e(TAG, "could not encrypt the passphrase; the previous value is kept", error)
        }
    }

    /**
     * Bring older storage up to date; safe to run on every start.
     * 1. The oldest versions kept the passphrase in plain text in the shared "cfg" file.
     * 2. The first credentials-file version kept it in plain text there.
     * Either value is encrypted into this file unless an encrypted value already exists,
     * and every plain-text copy is removed.
     */
    fun migrate(context: Context) {
        val cfg = context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE)
        val own = own(context)
        val legacy = own.getString(KEY_PLAIN, null) ?: cfg.getString(KEY_PLAIN, null)
        if (legacy == null && !cfg.contains(KEY_PLAIN) && !own.contains(KEY_PLAIN)) return

        if (!own.contains(KEY_ENCRYPTED) && legacy != null) {
            try {
                own.edit().putString(KEY_ENCRYPTED, encrypt(legacy)).commit()
            } catch (error: GeneralSecurityException) {
                // Leave the plain-text copy in place so the user's passphrase is not lost; retried next start.
                Log.e(TAG, "could not encrypt the stored passphrase; will retry", error)
                return
            }
        }
        own.edit().remove(KEY_PLAIN).commit()
        cfg.edit().remove(KEY_PLAIN).commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySource.key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        require(sealed.size > IV_BYTES) { "stored passphrase is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keySource.key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun own(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
