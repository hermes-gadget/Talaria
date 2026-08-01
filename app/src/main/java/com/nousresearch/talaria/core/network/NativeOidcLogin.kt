/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.nousresearch.talaria.core.network

import com.nousresearch.talaria.core.data.prefs.SecureConnectionStore
import com.nousresearch.talaria.domain.model.AuthProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

data class NativeOidcTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val provider: String,
    val userId: String,
)

data class NativeOidcProvider(
    val name: String,
    val displayName: String,
)

/** Pure RFC 7636 helpers, separated so the security checks have JVM tests. */
object NativeOidcPkce {
    data class Pair(val verifier: String, val challenge: String)
    class ProviderRejected(message: String) : IllegalStateException(message)

    fun generate(random: SecureRandom = SecureRandom()): Pair {
        val bytes = ByteArray(32).also(random::nextBytes)
        val verifier = base64Url(bytes)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Pair(verifier, challenge)
    }

    fun state(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(24).also(random::nextBytes))

    fun parseCallback(target: String, expectedState: String): String {
        val url = ("http://127.0.0.1" + target).toHttpUrlOrNull()
            ?: error("Hermes returned an invalid native-login callback")
        check(url.encodedPath == "/callback") { "Native-login callback used an unexpected path" }
        val state = url.queryParameter("state")
        check(expectedState.isNotBlank() && state == expectedState) {
            "Native-login callback state mismatch (possible CSRF)"
        }
        url.queryParameter("error")?.let { error ->
            val description = url.queryParameter("error_description").orEmpty()
            throw ProviderRejected(
                "Hermes rejected native login: $error${description.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
            )
        }
        return url.queryParameter("code")?.takeIf { it.isNotBlank() }
            ?: error("Hermes native-login callback did not contain a code")
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Android half of Hermes' advertised `native_pkce` flow: an ephemeral
 * loopback listener, system browser, PKCE code exchange, then encrypted token
 * persistence. Browser cookies are deliberately not used.
 */
class NativeOidcLogin(
    private val clientFactory: HermesClientFactory,
    private val store: SecureConnectionStore,
) {
    suspend fun providers(): List<NativeOidcProvider> = withContext(Dispatchers.IO) {
        clientFactory.api().authProviders().providers
            .filterNot(AuthProviderInfo::supports_password)
            .map { NativeOidcProvider(it.name, it.display_name ?: it.name) }
    }

    suspend fun signIn(
        profileId: String,
        provider: String?,
        openBrowser: (String) -> Unit,
    ): NativeOidcTokens = withContext(Dispatchers.IO) {
        val profile = store.profiles.value.firstOrNull { it.id == profileId }
            ?: error("Connection profile disappeared before sign-in")
        val status = clientFactory.api().getStatus()
        check("native_pkce" in status.auth_flows) {
            "This Hermes gateway does not advertise native PKCE login. Update Hermes, or use BASIC/SESSION_TOKEN."
        }

        val pkce = NativeOidcPkce.generate()
        val state = NativeOidcPkce.state()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = ACCEPT_POLL_MS
            val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
            val authorizeUrl = buildAuthorizeUrl(
                profile.baseUrl,
                pkce.challenge,
                redirectUri,
                state,
                provider,
            )
            withContext(Dispatchers.Main.immediate) { openBrowser(authorizeUrl) }

            val deadline = System.nanoTime() + LOGIN_TIMEOUT_MS * 1_000_000
            var code: String? = null
            while (code == null && System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        val target = requestLine.split(' ').getOrNull(1).orEmpty()
                        if (target.contains("code=") || target.contains("error=")) {
                            try {
                                code = NativeOidcPkce.parseCallback(target, state)
                                respondToBrowser(socket, success = true)
                            } catch (failure: IllegalStateException) {
                                respondToBrowser(socket, success = false)
                                // A provider denial belongs to this flow and is
                                // terminal. Random/mismatched loopback traffic is
                                // ignored so it cannot cancel the real browser tab.
                                if (failure is NativeOidcPkce.ProviderRejected) throw failure
                            }
                        } else {
                            respondToBrowser(socket, success = false)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Poll again so coroutine cancellation and the deadline work.
                }
            }
            val authorizationCode = code ?: error("Hermes browser sign-in timed out")
            val tokens = exchange(profile.baseUrl, authorizationCode, pkce.verifier)
            store.updateOidcTokens(
                id = profile.id,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAt = tokens.expiresAt,
                provider = tokens.provider,
            )
            clientFactory.invalidate()
            tokens
        }
    }

    private fun buildAuthorizeUrl(
        baseUrl: String,
        challenge: String,
        redirectUri: String,
        state: String,
        provider: String?,
    ): String {
        val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid dashboard URL")
        return base.newBuilder()
            .addPathSegments("auth/native/authorize")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .apply { provider?.takeIf { it.isNotBlank() }?.let { addQueryParameter("provider", it) } }
            .build()
            .toString()
    }

    private fun exchange(baseUrl: String, code: String, verifier: String): NativeOidcTokens {
        val base = baseUrl.toHttpUrlOrNull() ?: error("Invalid dashboard URL")
        val url = base.newBuilder().addPathSegments("auth/native/token").build()
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()
        return clientFactory.okHttp().newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Hermes token exchange failed (${response.code}): ${text.take(240)}"
            }
            val json = JsonConfig.json.parseToJsonElement(text) as? JsonObject
                ?: error("Hermes returned an invalid native token response")
            val access = json["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            check(access.isNotBlank()) { "Hermes token response did not include access_token" }
            NativeOidcTokens(
                accessToken = access,
                refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull ?: 0,
                provider = json["provider"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                userId = json["user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    private fun respondToBrowser(socket: java.net.Socket, success: Boolean) {
        val title = if (success) "Signed in" else "Callback rejected"
        val message = if (success) {
            "You can close this tab and return to Talaria."
        } else {
            "This was not the active Talaria sign-in callback. Return to the original browser tab."
        }
        val html = """
            <!doctype html><meta charset="utf-8"><title>$title</title>
            <body style="font:16px sans-serif;text-align:center;margin:3rem">
            <h2>$title</h2><p>$message</p></body>
        """.trimIndent()
        val bytes = html.toByteArray()
        socket.getOutputStream().bufferedWriter().use { writer ->
            writer.write("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\n")
            writer.write("Content-Type: text/html; charset=utf-8\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(html)
            writer.flush()
        }
    }

    private companion object {
        const val ACCEPT_POLL_MS = 1_000
        const val LOGIN_TIMEOUT_MS = 5 * 60 * 1_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
