package com.thegalaxybyte.momentum.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thegalaxybyte.momentum.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsStore by preferencesDataStore("settings")
private val Context.secretsStore by preferencesDataStore("secrets")

data class SyncStatus(val lastSyncAt: Long, val lastError: String)

/** Device-local settings (not synced): API URL, sync cursor and status. */
class Prefs(private val context: Context) {
    private val apiUrlKey = stringPreferencesKey("api_url")
    private val cursorKey = stringPreferencesKey("sync_cursor")
    private val lastSyncKey = longPreferencesKey("last_sync_at")
    private val lastErrorKey = stringPreferencesKey("last_sync_error")
    private val coachSeenKey = longPreferencesKey("coach_review_seen")

    val apiUrl: Flow<String> = context.settingsStore.data.map { it[apiUrlKey] ?: BuildConfig.DEFAULT_API_URL }
    suspend fun apiUrlNow(): String = apiUrl.first()
    suspend fun setApiUrl(url: String) = context.settingsStore.edit { it[apiUrlKey] = url.trim().trimEnd('/') }

    suspend fun cursor(): String = context.settingsStore.data.first()[cursorKey] ?: ""
    suspend fun setCursor(c: String) = context.settingsStore.edit { it[cursorKey] = c }

    val syncStatus: Flow<SyncStatus> = context.settingsStore.data.map {
        SyncStatus(it[lastSyncKey] ?: 0L, it[lastErrorKey] ?: "")
    }
    /** createdAt of the newest weekly review the user has opened (for the "review ready" card). */
    val coachSeen: Flow<Long> = context.settingsStore.data.map { it[coachSeenKey] ?: 0L }
    suspend fun setCoachSeen(createdAt: Long) = context.settingsStore.edit { it[coachSeenKey] = createdAt }

    suspend fun setSyncResult(at: Long, error: String) = context.settingsStore.edit {
        if (error.isEmpty()) it[lastSyncKey] = at
        it[lastErrorKey] = error
    }
}

/**
 * The API token, encrypted with an AES-GCM key that never leaves the Android Keystore.
 * Excluded from backups (the key can't be restored on another device anyway).
 */
class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("api_token")
    private val alias = "momentum-api-token"

    val hasToken: Flow<Boolean> = context.secretsStore.data.map { !it[tokenKey].isNullOrEmpty() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    suspend fun set(token: String) {
        val clean = token.uppercase().filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) {
            context.secretsStore.edit { it.remove(tokenKey) }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(clean.toByteArray())
        context.secretsStore.edit { it[tokenKey] = Base64.encodeToString(blob, Base64.NO_WRAP) }
    }

    suspend fun get(): String? {
        val stored = context.secretsStore.data.first()[tokenKey] ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        }.getOrNull()
    }
}
