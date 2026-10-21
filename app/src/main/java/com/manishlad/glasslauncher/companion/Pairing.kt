package com.manishlad.glasslauncher.companion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What the TV's pairing screen shows. */
sealed class PairingState {
    data object Idle : PairingState()
    data class Waiting(val code: String, val expiresAt: Long) : PairingState()
    data object Paired : PairingState()
}

/**
 * Phone pairing: a 4-digit code shown on the TV, valid for 5 minutes and only while the
 * pairing screen is open; 5 wrong tries replace it. A correct code yields a long-lived
 * session token. Only SHA-256 hashes of tokens are stored on the TV.
 */
class Pairing(context: Context) {
    private val random = SecureRandom()
    private val sessionsFile = File(context.filesDir, "companion_sessions.json")
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state
    private var wrongTries = 0

    @Synchronized
    fun start() {
        wrongTries = 0
        _state.value = PairingState.Waiting(
            code = "%04d".format(random.nextInt(10_000)),
            expiresAt = System.currentTimeMillis() + 5 * 60_000,
        )
    }

    @Synchronized
    fun cancel() {
        _state.value = PairingState.Idle
    }

    sealed class Result {
        class Ok(val token: String) : Result()
        data object NotOpen : Result()
        data object Expired : Result()
        data object Wrong : Result()
    }

    /** Checks a code from the phone; on success returns a new session token. */
    @Synchronized
    fun pair(code: String, deviceLabel: String): Result {
        val s = _state.value as? PairingState.Waiting ?: return Result.NotOpen
        if (System.currentTimeMillis() > s.expiresAt) return Result.Expired
        if (!MessageDigest.isEqual(code.trim().toByteArray(), s.code.toByteArray())) {
            if (++wrongTries >= 5) start() // new code on the TV
            return Result.Wrong
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val sessions = readSessions()
        sessions.put(JSONObject().put("hash", hash(token)).put("label", deviceLabel.take(80)).put("created", System.currentTimeMillis()))
        sessionsFile.writeText(sessions.toString())
        _state.value = PairingState.Paired
        return Result.Ok(token)
    }

    @Synchronized
    fun isValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val h = hash(token)
        val sessions = readSessions()
        return (0 until sessions.length()).any { sessions.getJSONObject(it).getString("hash") == h }
    }

    @Synchronized
    fun revoke(token: String?) {
        if (token == null) return
        val h = hash(token)
        val sessions = readSessions()
        val kept = JSONArray((0 until sessions.length()).map { sessions.getJSONObject(it) }.filter { it.getString("hash") != h })
        sessionsFile.writeText(kept.toString())
    }

    /** Forget every paired phone. */
    @Synchronized
    fun revokeAll() {
        sessionsFile.delete()
    }

    @Synchronized
    fun pairedCount() = readSessions().length()

    private fun readSessions() = if (sessionsFile.exists()) JSONArray(sessionsFile.readText()) else JSONArray()

    private fun hash(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
