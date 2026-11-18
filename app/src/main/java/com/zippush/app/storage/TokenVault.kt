package com.zippush.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores GitHub Personal Access Tokens encrypted-at-rest with a hardware-backed
 * Android Keystore key (AES/GCM/NoPadding). Tokens never leave the device in
 * plain text at rest.
 *
 * Supports multiple accounts keyed by GitHub login, plus the login of the
 * currently active account. The legacy single-token format is migrated
 * automatically on first read.
 */
class TokenVault(context: Context) {

    /** One stored account: its GitHub login and encrypted token material. */
    data class StoredAccount(
        val login: String,
        val tokenPreview: String
    )

    data class AccountData(
        val login: String,
        val token: String
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ crypto

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "|" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        return try {
            val parts = stored.split("|")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt stored token", e)
            null
        }
    }

    // --------------------------------------------------------------- migration

    /** Converts the pre-1.2 single-token storage to the accounts map, if any. */
    private fun migrateLegacyIfNeeded() {
        val legacyIv = prefs.getString(LEGACY_KEY_IV, null)
        val legacyCipher = prefs.getString(LEGACY_KEY_TOKEN, null)
        if (legacyIv == null || legacyCipher == null) return
        try {
            val iv = Base64.decode(legacyIv, Base64.NO_WRAP)
            val encrypted = Base64.decode(legacyCipher, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val token = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            // Store under the "pending" key; the login is resolved and the
            // account renamed when the token is next verified.
            prefs.edit()
                .putString(accountKey(PENDING_LOGIN), encrypt(token))
                .remove(LEGACY_KEY_IV)
                .remove(LEGACY_KEY_TOKEN)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy token migration failed", e)
            prefs.edit().remove(LEGACY_KEY_IV).remove(LEGACY_KEY_TOKEN).apply()
        }
    }

    // ---------------------------------------------------------------- accounts

    private fun accountKey(login: String) = "acct_$login"

    /** All stored accounts (login + masked token preview), in storage order. */
    fun listAccounts(): List<StoredAccount> {
        migrateLegacyIfNeeded()
        return prefs.all.keys
            .filter { it.startsWith("acct_") && it != accountKey(PENDING_LOGIN) }
            .map { key ->
                val login = key.removePrefix("acct_")
                val token = decrypt(prefs.getString(key, null) ?: "")
                StoredAccount(login = login, tokenPreview = preview(token))
            }
            .sortedBy { it.login.lowercase() }
    }

    /** Masked preview like "ghp_••••1234" for the account switcher rows. */
    private fun preview(token: String?): String {
        if (token.isNullOrBlank()) return ""
        val tail = if (token.length <= 4) token else token.takeLast(4)
        val prefix = token.takeWhile { it != '_' }.let { if (it.length > 8) it.take(8) else it }
        return "$prefix••••$tail"
    }

    /** Saves a token. If [login] is null the token is stored as pending until verified. */
    fun saveAccount(login: String?, token: String) {
        if (login != null) removePending()
        prefs.edit().putString(accountKey(login ?: PENDING_LOGIN), encrypt(token)).apply()
        if (login != null) prefs.edit().putString(KEY_ACTIVE, login).apply()
    }

    /** The pending (not yet verified) token, if any. */
    fun loadPendingToken(): String? {
        migrateLegacyIfNeeded()
        val stored = prefs.getString(accountKey(PENDING_LOGIN), null) ?: return null
        return decrypt(stored)
    }

    private fun removePending() {
        prefs.edit().remove(accountKey(PENDING_LOGIN)).apply()
    }

    /** Full token for [login], or null. */
    fun loadAccountToken(login: String): String? {
        return decrypt(prefs.getString(accountKey(login), null) ?: return null)
    }

    /** The login of the active account, or null. */
    fun activeLogin(): String? = prefs.getString(KEY_ACTIVE, null)

    /** Marks [login] as the active account. */
    fun setActiveLogin(login: String) {
        prefs.edit().putString(KEY_ACTIVE, login).apply()
    }

    /** Deletes the account with [login]; clears active if it was selected. */
    fun removeAccount(login: String) {
        prefs.edit().remove(accountKey(login)).apply()
        if (activeLogin() == login) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    /** Number of stored accounts (pending excluded). */
    fun accountCount(): Int = listAccounts().size

    // --------------------------------------------------- legacy single-account

    /** @deprecated use the account APIs above. Kept for rollback safety. */
    fun saveToken(token: String) = saveAccount(null, token)

    /** @deprecated loads pending token or the active account's token. */
    fun loadToken(): String? = loadPendingToken() ?: activeLogin()?.let { loadAccountToken(it) }

    fun clearToken() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "TokenVault"
        private const val PREFS_NAME = "zippush_secure_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zippush_token_key"
        private const val KEY_ACTIVE = "active_login"
        private const val PENDING_LOGIN = "__pending__"
        private const val LEGACY_KEY_IV = "token_iv"
        private const val LEGACY_KEY_TOKEN = "token_cipher"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
