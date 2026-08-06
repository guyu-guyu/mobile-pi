package dev.mobilepi.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ProviderProfile(
    val provider: String,
    val model: String,
    val apiKey: String,
) {
    init {
        require(provider.isNotBlank()) { "Provider is required" }
        require(model.isNotBlank()) { "Model is required" }
        require(apiKey.isNotBlank()) { "API key is required" }
    }
}

class CredentialStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ProviderProfileStore(context: Context) {
    private val atomicFile = AtomicFile(
        File(context.applicationContext.noBackupFilesDir, "credentials/provider-profile.json"),
    )
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun load(): ProviderProfile? {
        if (!atomicFile.baseFile.isFile) return null
        return try {
            val envelope = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                json.decodeFromString<EncryptedEnvelope>(reader.readText())
            }
            if (envelope.schemaVersion != ENVELOPE_SCHEMA_VERSION) {
                throw CredentialStorageException(
                    "Unsupported encrypted profile version: ${envelope.schemaVersion}",
                )
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(envelope.iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(envelope.ciphertext, Base64.NO_WRAP))
            val profile = json.decodeFromString<ProfilePayload>(
                plaintext.toString(StandardCharsets.UTF_8),
            )
            if (profile.schemaVersion != PROFILE_SCHEMA_VERSION) {
                throw CredentialStorageException(
                    "Unsupported provider profile version: ${profile.schemaVersion}",
                )
            }
            ProviderProfile(profile.provider, profile.model, profile.apiKey)
        } catch (error: CredentialStorageException) {
            throw error
        } catch (error: Throwable) {
            throw CredentialStorageException("Cannot decrypt the saved provider profile", error)
        }
    }

    fun save(profile: ProviderProfile) {
        try {
            val plaintext = json.encodeToString(
                ProfilePayload(
                    schemaVersion = PROFILE_SCHEMA_VERSION,
                    provider = profile.provider.trim(),
                    model = profile.model.trim(),
                    apiKey = profile.apiKey.trim(),
                ),
            ).toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val envelope = EncryptedEnvelope(
                schemaVersion = ENVELOPE_SCHEMA_VERSION,
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP),
            )
            writeAtomically(json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8))
        } catch (error: CredentialStorageException) {
            throw error
        } catch (error: Throwable) {
            throw CredentialStorageException("Cannot encrypt the provider profile", error)
        }
    }

    fun clear() {
        atomicFile.delete()
        if (atomicFile.baseFile.exists()) {
            throw CredentialStorageException("Cannot delete the saved provider profile")
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        atomicFile.baseFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw CredentialStorageException("Cannot create private credential storage")
            }
        }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun requireKey(): SecretKey {
        val store = keyStore()
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw CredentialStorageException("The Android Keystore profile key is missing")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Serializable
    private data class ProfilePayload(
        val schemaVersion: Int,
        val provider: String,
        val model: String,
        val apiKey: String,
    )

    @Serializable
    private data class EncryptedEnvelope(
        val schemaVersion: Int,
        val iv: String,
        val ciphertext: String,
    )

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.mobilepi.provider-profile.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PROFILE_SCHEMA_VERSION = 1
        private const val ENVELOPE_SCHEMA_VERSION = 1
    }
}
