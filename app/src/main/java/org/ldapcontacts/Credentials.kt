package org.ldapcontacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's directory login and password, encrypted with an AES-GCM key that never
 * leaves the Android Keystore. The stored blob is useless without this device, and
 * the key cannot be read back, not even by this app: it can only be used to decrypt.
 *
 * The password is needed because the app binds to the LDAP server as the user. It is
 * decrypted only to open an LDAP connection, never logged, never put in
 * AccountManager (which keeps account passwords in clear text) and never backed up
 * (allowBackup=false plus data_extraction_rules).
 *
 * The key is not bound to user authentication on purpose: the directory provider and
 * the caller ID both run with no user present.
 */
object Credentials {
    private const val TAG = "LdapContacts"
    private const val PREFS = "credentials"
    private const val KEY_BLOB = "blob"
    private const val KEY_ALIAS = "credentials"

    /** Nothing stored yet: the user has not set the account up. */
    class NotSet : Exception("no directory credentials stored")

    /**
     * The directory refused the stored password. Every further attempt stops until the
     * user types it again: Contacts sends up to three queries per keystroke, so retrying
     * a wrong password would walk the directory account into a lockout within seconds.
     */
    class Rejected : Exception("directory password rejected")

    class Secret(val login: String, val password: String)

    private var cached: Secret? = null

    /**
     * Stores credentials that have just been checked against the directory.
     *
     * The LdapClient calls stay outside the lock on purpose: a search holds the
     * LdapClient monitor and asks for credentials, so taking the two in the other
     * order here would deadlock.
     */
    fun save(ctx: Context, login: String, password: String) {
        store(ctx, login, password)
        LdapClient.reset()
        LdapClient.clearMemo()
    }

    @Synchronized
    private fun store(ctx: Context, login: String, password: String) {
        val o = JSONObject().put("u", login).put("p", password)
        // commit(): the password must not be lost if the process dies right after.
        prefs(ctx).edit().putString(KEY_BLOB, encrypt(o.toString())).commit()
        cached = Secret(login, password)
        Store.setAccount(ctx, login)
        Store.setPasswordRejected(ctx, false)
        Store.cancelPasswordRejected(ctx)
    }

    /** Throws [NotSet] when nothing is stored, [Rejected] after a refusal. */
    @Synchronized
    fun require(ctx: Context): Secret {
        if (Store.passwordRejected(ctx)) throw Rejected()
        return load(ctx) ?: throw NotSet()
    }

    @Synchronized
    fun load(ctx: Context): Secret? {
        cached?.let { return it }
        val blob = prefs(ctx).getString(KEY_BLOB, null) ?: return null
        val s = runCatching {
            val o = JSONObject(decrypt(blob))
            Secret(o.getString("u"), o.getString("p"))
        }.onFailure { Log.w(TAG, "stored credentials unreadable", it) }.getOrNull()
        cached = s
        return s
    }

    /**
     * Forgets the password and the account. [removeAccount] is false when the system
     * is already removing the account itself. LdapClient is dropped outside the lock,
     * for the reason given on [save].
     */
    fun signOut(ctx: Context, removeAccount: Boolean = true) {
        clear(ctx, removeAccount)
        LdapClient.reset()
        LdapClient.clearMemo()
    }

    @Synchronized
    private fun clear(ctx: Context, removeAccount: Boolean) {
        cached = null
        prefs(ctx).edit().clear().commit()
        Store.setPasswordRejected(ctx, false)
        Store.cancelPasswordRejected(ctx)
        if (removeAccount) Store.removeAccount(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}
