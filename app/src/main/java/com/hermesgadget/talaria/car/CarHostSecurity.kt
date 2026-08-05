/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hermesgadget.talaria.car

import com.hermesgadget.talaria.R
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.HostInfo
import androidx.car.app.R as CarAppR
import androidx.car.app.validation.HostValidator
import com.hermesgadget.talaria.core.data.prefs.CarHostIdentity
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustRecord
import com.hermesgadget.talaria.core.data.prefs.CarHostTrustStore
import java.security.MessageDigest
import android.annotation.SuppressLint

/**
 * The exact package/certificate pairs shipped by androidx.car.app:app.
 *
 * Provenance: `androidx.car.app:app:1.7.0`, resource
 * `res/values/values.xml#hosts_allowlist_sample`. Reading the dependency's
 * resource keeps Talaria aligned when that allowlist changes in a future bump.
 */
object AndroidxKnownCarHosts {
    @SuppressLint("PrivateResource") // Intentional: tracks the androidx allowlist (see header comment).
    fun identities(context: Context): Set<CarHostIdentity> = context.resources
        .getStringArray(CarAppR.array.hosts_allowlist_sample)
        .mapNotNull { entry ->
            val fields = entry.split(',', limit = 2)
            if (fields.size == 2) CarHostIdentity.create(fields[1], fields[0]) else null
        }
        .toSet()
}

/** Resolve the binder caller to one unambiguous current signing certificate. */
object CarHostIdentityResolver {
    fun resolve(context: Context, hostInfo: HostInfo): CarHostIdentity? {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                hostInfo.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return null
        if (packageInfo.applicationInfo?.uid != hostInfo.uid) return null
        val signers = packageInfo.signingInfo?.apkContentsSigners ?: return null
        // A single fingerprint is the trust-store contract. Reject ambiguous
        // multi-signer packages instead of accepting any signer implicitly.
        if (signers.size != 1) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
        val fingerprint = digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return CarHostIdentity.create(hostInfo.packageName, fingerprint)
    }
}

enum class CarHostTrustLevel {
    DEBUG,
    ANDROIDX_KNOWN,
    MANUALLY_ENROLLED,
    UNTRUSTED,
}

data class CarHostAccessDecision(
    val level: CarHostTrustLevel,
    val canReadTranscripts: Boolean,
    val canPerformActions: Boolean,
)

/** Pure certificate-bound policy shared by the screen gate and unit tests. */
object CarHostTrustPolicy {
    const val ACTION_APPROVAL_WINDOW_MILLIS = 15 * 60 * 1000L

    fun decide(
        debugBuild: Boolean,
        identity: CarHostIdentity?,
        knownHosts: Set<CarHostIdentity>,
        storedRecord: CarHostTrustRecord?,
        nowMillis: Long,
    ): CarHostAccessDecision {
        if (debugBuild) {
            return CarHostAccessDecision(CarHostTrustLevel.DEBUG, true, true)
        }
        if (identity == null) {
            return CarHostAccessDecision(CarHostTrustLevel.UNTRUSTED, false, false)
        }
        if (identity in knownHosts) {
            return CarHostAccessDecision(CarHostTrustLevel.ANDROIDX_KNOWN, true, true)
        }
        val manuallyEnrolled = storedRecord?.identity == identity && storedRecord.enrolledAt != null
        if (!manuallyEnrolled) {
            return CarHostAccessDecision(CarHostTrustLevel.UNTRUSTED, false, false)
        }
        val approvalAge = storedRecord.actionApprovedAt?.let { nowMillis - it }
        val recentlyApproved = approvalAge != null &&
            approvalAge in 0..ACTION_APPROVAL_WINDOW_MILLIS
        return CarHostAccessDecision(
            level = CarHostTrustLevel.MANUALLY_ENROLLED,
            canReadTranscripts = true,
            canPerformActions = recentlyApproved,
        )
    }
}

/** Creates the AndroidX handshake validator without weakening release builds. */
object CarHostValidatorFactory {
    @SuppressLint("PrivateResource") // Intentional: androidx host allowlist, same provenance as above.
    fun create(
        context: Context,
        debugBuild: Boolean,
        trustStore: CarHostTrustStore,
    ): HostValidator {
        if (debugBuild) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        return HostValidator.Builder(context)
            .addAllowedHosts(CarAppR.array.hosts_allowlist_sample)
            .apply {
                trustStore.listEnrolledIdentities().forEach { identity ->
                    addAllowedHost(identity.packageName, identity.certificateSha256)
                }
            }
            .build()
    }
}

/**
 * Per-session authorization remains mandatory even after AndroidX validation.
 * AndroidX also admits local/system/template-renderer callers by design; this
 * second gate makes those callers observable but discloses nothing until their
 * exact certificate is known or explicitly enrolled.
 */
class CarHostSessionAuthorizer(
    private val debugBuild: Boolean,
    val identity: CarHostIdentity?,
    private val knownHosts: Set<CarHostIdentity>,
    private val trustStore: CarHostTrustStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun startSession() {
        if (debugBuild) return
        identity?.let(trustStore::observe)
        if (access().canReadTranscripts) identity?.let(trustStore::touchLastUsed)
    }

    fun access(): CarHostAccessDecision = CarHostTrustPolicy.decide(
        debugBuild = debugBuild,
        identity = identity,
        knownHosts = knownHosts,
        storedRecord = identity?.let { trustStore.recordFor(it.packageName) },
        nowMillis = nowMillis(),
    )

    fun authorizeAction(action: String, context: Context): Result<CarHostIdentity?> {
        val decision = access()
        if (!decision.canPerformActions) {
            val message = if (decision.level == CarHostTrustLevel.MANUALLY_ENROLLED) {
                context.getString(R.string.car_confirm_host)
            } else {
                context.getString(R.string.car_host_not_trusted)
            }
            return Result.failure(SecurityException(message))
        }
        if (!debugBuild) identity?.let { trustStore.recordAction(it, action, nowMillis()) }
        return Result.success(identity)
    }

    fun addTrustListener(listener: () -> Unit) = trustStore.addListener(listener)

    fun removeTrustListener(listener: () -> Unit) = trustStore.removeListener(listener)
}
