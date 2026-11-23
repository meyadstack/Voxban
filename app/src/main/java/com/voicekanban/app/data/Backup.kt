package com.voicekanban.app.data

import android.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export / import of all app data as one JSON file, so a reinstall (or a new
 * phone) can get everything back. Boards, notes, chats, money and settings are
 * plain JSON. Saved passwords are only included when the user picks a backup
 * password: they're then encrypted with AES-256-GCM using a key derived from
 * it (PBKDF2-SHA256). API keys are never exported.
 */
object Backup {
    const val FORMAT = 1
    private const val ITERATIONS = 210_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val vaultSer = ListSerializer(PasswordEntry.serializer())

    class Parsed(val state: AppState, val exportedAt: Long, private val vaultBlob: JsonObject?) {
        val hasPasswords: Boolean get() = vaultBlob != null
        /** Throws [IllegalArgumentException] when the backup password is wrong. */
        fun vault(passphrase: String): List<PasswordEntry> {
            val b = vaultBlob ?: return emptyList()
            fun f(k: String) = Base64.decode(b[k]!!.jsonPrimitive.content, Base64.NO_WRAP)
            val plain = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(passphrase, f("salt"), b["iterations"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ITERATIONS), GCMParameterSpec(128, f("iv")))
                String(c.doFinal(f("data")), Charsets.UTF_8)
            } catch (e: Exception) {
                throw IllegalArgumentException("Wrong backup password.")
            }
            return json.decodeFromString(vaultSer, plain)
        }
    }

    private fun key(pass: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, iterations, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun export(state: AppState, vault: List<PasswordEntry>, passphrase: String?): String {
        val root = buildJsonObject {
            put("app", "Voxban")
            put("format", FORMAT)
            put("exportedAt", System.currentTimeMillis())
            put("state", json.encodeToJsonElement(AppState.serializer(), state.copy(chat = emptyList())))
            if (passphrase != null && vault.isNotEmpty()) {
                val rnd = SecureRandom()
                val salt = ByteArray(16).also(rnd::nextBytes)
                val iv = ByteArray(12).also(rnd::nextBytes)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
                val data = c.doFinal(json.encodeToString(vaultSer, vault).toByteArray(Charsets.UTF_8))
                put("vault", buildJsonObject {
                    put("cipher", "AES-256-GCM")
                    put("kdf", "PBKDF2WithHmacSHA256")
                    put("iterations", ITERATIONS)
                    put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    put("data", Base64.encodeToString(data, Base64.NO_WRAP))
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Throws with a readable message when the file isn't a Voxban backup. */
    fun parse(text: String): Parsed {
        val root = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw IllegalArgumentException("That file isn’t valid JSON.") }
        val stateEl = root["state"] ?: throw IllegalArgumentException("That file isn’t a Voxban backup.")
        val state = try { json.decodeFromJsonElement(AppState.serializer(), stateEl) } catch (e: Exception) {
            throw IllegalArgumentException("This backup couldn’t be read (${e.message?.take(80)}).")
        }
        return Parsed(state, root["exportedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L, root["vault"] as? JsonObject)
    }
}
