package com.kingzcheung.xime.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

interface KeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun putEntries(entries: Map<String, String?>)
    fun getAllKeys(): Set<String>
    fun remove(key: String)
    fun clearAll()
}

class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences
) : KeyValueStore {
    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putEntries(entries: Map<String, String?>) {
        val editor = prefs.edit()
        for ((k, v) in entries) {
            if (v.isNullOrEmpty()) {
                editor.remove(k)
            } else {
                editor.putString(k, v)
            }
        }
        editor.apply()
    }

    override fun getAllKeys(): Set<String> = prefs.all.keys.toSet()

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}

class InMemoryKeyValueStore : KeyValueStore {
    private val strings = ConcurrentHashMap<String, String>()
    private val booleans = ConcurrentHashMap<String, Boolean>()

    override fun getString(key: String, defaultValue: String?): String? =
        strings[key] ?: defaultValue

    override fun putString(key: String, value: String?) {
        if (value == null) {
            strings.remove(key)
        } else {
            strings[key] = value
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        booleans[key] ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun putEntries(entries: Map<String, String?>) {
        for ((k, v) in entries) {
            if (v.isNullOrEmpty()) {
                strings.remove(k)
            } else {
                strings[k] = v
            }
        }
    }

    override fun getAllKeys(): Set<String> = strings.keys.toSet()

    override fun remove(key: String) {
        strings.remove(key)
        booleans.remove(key)
    }

    override fun clearAll() {
        strings.clear()
        booleans.clear()
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher for encrypting API keys at rest.
 */
class AndroidKeyStoreAesGcmCipher(
    private val keyAlias: String = SecretVault.DEFAULT_KEY_ALIAS
) : SecretCipher {

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(cipherBytes)
        return "v1:$ivB64:$ctB64"
    }

    override fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        val parts = cipherText.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported ciphertext envelope" }
        val iv = Base64.getDecoder().decode(parts[1])
        val ct = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(ct)
        return String(plainBytes, Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * Standard JCE AES-256-GCM cipher for JVM unit tests where AndroidKeyStore provider is not mounted.
 */
class JvmAesGcmCipher(
    rawKeyBytes: ByteArray = ByteArray(32) { (it * 7 + 13).toByte() }
) : SecretCipher {
    private val secretKey: SecretKey = SecretKeySpec(rawKeyBytes.copyOf(32), "AES")
    private val secureRandom = SecureRandom()

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        return "v1:$ivB64:$ctB64"
    }

    override fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        val parts = cipherText.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported ciphertext envelope" }
        val iv = Base64.getDecoder().decode(parts[1])
        val ct = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}

/**
 * Isolated encrypted secret storage for TypeFree API keys (`sttConfig.apiKey`, `llmConfig.apiKey`,
 * `multimodalConfig.apiKey`, and `providerVault` credentials).
 */
class SecretVault(
    val store: KeyValueStore,
    private val cipher: SecretCipher
) {

    fun putSecret(key: String, plainText: String) {
        val clean = plainText.trim()
        if (clean.isEmpty()) {
            store.remove(key)
        } else {
            val encrypted = runCatching { cipher.encrypt(clean) }.getOrDefault("")
            if (encrypted.isNotEmpty()) {
                store.putString(key, encrypted)
            }
        }
    }

    fun getSecret(key: String): String {
        val rawCipher = store.getString(key, null) ?: return ""
        if (rawCipher.isBlank()) return ""
        return runCatching { cipher.decrypt(rawCipher) }.getOrDefault("")
    }

    fun getRawCiphertext(key: String): String? = store.getString(key, null)

    fun saveSecretsFromSettings(settings: AppSettings) {
        val updates = LinkedHashMap<String, String?>()

        fun encodeOrNull(plain: String): String? {
            val trimmed = plain.trim()
            if (trimmed.isEmpty()) return null
            return runCatching { cipher.encrypt(trimmed) }.getOrNull()
        }

        updates[KEY_STT_API_KEY] = encodeOrNull(settings.sttConfig.apiKey)
        updates[KEY_LLM_API_KEY] = encodeOrNull(settings.llmConfig.apiKey)
        updates[KEY_MM_API_KEY] = encodeOrNull(settings.multimodalConfig.apiKey)
        updates[KEY_JEV_API_KEY] = encodeOrNull(settings.jevLlmConfig.apiKey)

        // Track existing vault secret keys so removed provider entries are also cleaned up
        val activeVaultSecretKeys = HashSet<String>()
        for ((providerId, cred) in settings.providerVault) {
            val id = providerId.ifBlank { cred.providerId }.trim()
            if (id.isEmpty()) continue
            val apiSlot = vaultApiKeyName(id)
            val sttSlot = vaultSttApiKeyName(id)
            activeVaultSecretKeys.add(apiSlot)
            activeVaultSecretKeys.add(sttSlot)
            updates[apiSlot] = encodeOrNull(cred.apiKey)
            updates[sttSlot] = encodeOrNull(cred.sttApiKey)
        }

        for (existingKey in store.getAllKeys()) {
            if (existingKey.startsWith(PREFIX_VAULT_SECRET) && existingKey !in activeVaultSecretKeys) {
                updates[existingKey] = null
            }
        }

        store.putEntries(updates)
    }

    fun hydrateSettingsWithSecrets(settings: AppSettings): AppSettings {
        val sttKey = settings.sttConfig.apiKey.ifBlank { getSecret(KEY_STT_API_KEY) }
        val llmKey = settings.llmConfig.apiKey.ifBlank { getSecret(KEY_LLM_API_KEY) }
        val mmKey = settings.multimodalConfig.apiKey.ifBlank { getSecret(KEY_MM_API_KEY) }
        val jevKey = settings.jevLlmConfig.apiKey.ifBlank { getSecret(KEY_JEV_API_KEY) }

        val mergedVault = LinkedHashMap<String, ProviderCredential>()
        for ((providerId, cred) in settings.providerVault) {
            val id = providerId.ifBlank { cred.providerId }.trim()
            if (id.isEmpty()) continue
            val resolvedApiKey = cred.apiKey.ifBlank { getSecret(vaultApiKeyName(id)) }
            val resolvedSttKey = cred.sttApiKey.ifBlank { getSecret(vaultSttApiKeyName(id)) }
            mergedVault[id] = cred.copy(
                providerId = id,
                apiKey = resolvedApiKey,
                sttApiKey = resolvedSttKey
            )
        }

        // Also recover any provider credentials stored in SecretVault even if providerVault map was empty
        for (secretKey in store.getAllKeys()) {
            if (!secretKey.startsWith(PREFIX_VAULT_SECRET)) continue
            val providerId = when {
                secretKey.endsWith(SUFFIX_STT_API_KEY) ->
                    secretKey.removePrefix(PREFIX_VAULT_SECRET).removeSuffix(SUFFIX_STT_API_KEY)
                secretKey.endsWith(SUFFIX_API_KEY) ->
                    secretKey.removePrefix(PREFIX_VAULT_SECRET).removeSuffix(SUFFIX_API_KEY)
                else -> ""
            }
            if (providerId.isNotBlank() && providerId !in mergedVault) {
                val recoveredApi = getSecret(vaultApiKeyName(providerId))
                val recoveredStt = getSecret(vaultSttApiKeyName(providerId))
                if (recoveredApi.isNotBlank() || recoveredStt.isNotBlank()) {
                    mergedVault[providerId] = ProviderCredential(
                        providerId = providerId,
                        apiKey = recoveredApi,
                        sttApiKey = recoveredStt
                    )
                }
            }
        }

        val withVault = settings.copy(
            sttConfig = settings.sttConfig.copy(apiKey = sttKey),
            llmConfig = settings.llmConfig.copy(apiKey = llmKey),
            multimodalConfig = settings.multimodalConfig.copy(apiKey = mmKey),
            jevLlmConfig = settings.jevLlmConfig.copy(apiKey = jevKey),
            providerVault = mergedVault
        )
        return withVault.copy(
            providerVault = UnifiedProviderCatalog.hydrateVaultFromSettings(withVault)
        )
    }

    fun stripSecretsForPublicPersistence(settings: AppSettings): AppSettings {
        val sanitizedVault = settings.providerVault.mapValues { (_, cred) ->
            cred.copy(apiKey = "", sttApiKey = "")
        }
        return settings.copy(
            sttConfig = settings.sttConfig.copy(apiKey = ""),
            llmConfig = settings.llmConfig.copy(apiKey = ""),
            multimodalConfig = settings.multimodalConfig.copy(apiKey = ""),
            jevLlmConfig = settings.jevLlmConfig.copy(apiKey = ""),
            providerVault = sanitizedVault
        )
    }

    companion object {
        const val PREFS_NAME = "typefree_secure_secrets"
        const val DEFAULT_KEY_ALIAS = "typefree_master_key_v1"

        const val KEY_STT_API_KEY = "sec_stt_api_key"
        const val KEY_LLM_API_KEY = "sec_llm_api_key"
        const val KEY_MM_API_KEY = "sec_mm_api_key"
        const val KEY_JEV_API_KEY = "sec_jev_api_key"

        private const val PREFIX_VAULT_SECRET = "sec_vault_"
        private const val SUFFIX_API_KEY = "_api_key"
        private const val SUFFIX_STT_API_KEY = "_stt_api_key"

        fun vaultApiKeyName(providerId: String): String =
            "$PREFIX_VAULT_SECRET${providerId.trim()}$SUFFIX_API_KEY"

        fun vaultSttApiKeyName(providerId: String): String =
            "$PREFIX_VAULT_SECRET${providerId.trim()}$SUFFIX_STT_API_KEY"

        fun fromContext(
            context: Context,
            cipher: SecretCipher = AndroidKeyStoreAesGcmCipher()
        ): SecretVault {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SecretVault(
                store = SharedPreferencesKeyValueStore(prefs),
                cipher = cipher
            )
        }

        fun inMemory(cipher: SecretCipher = JvmAesGcmCipher()): SecretVault {
            return SecretVault(
                store = InMemoryKeyValueStore(),
                cipher = cipher
            )
        }

        fun containsPlaintextSecrets(settings: AppSettings): Boolean {
            if (settings.sttConfig.apiKey.isNotBlank()) return true
            if (settings.llmConfig.apiKey.isNotBlank()) return true
            if (settings.multimodalConfig.apiKey.isNotBlank()) return true
            if (settings.jevLlmConfig.apiKey.isNotBlank()) return true
            return settings.providerVault.values.any {
                it.apiKey.isNotBlank() || it.sttApiKey.isNotBlank()
            }
        }
    }
}
