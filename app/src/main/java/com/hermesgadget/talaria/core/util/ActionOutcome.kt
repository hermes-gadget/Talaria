/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0
 */
package com.hermesgadget.talaria.core.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Q05: typed application-success validation for mutation endpoints that answer
 * `{ok: true/false, ...}` (or `{error/detail: ...}` on failure). HTTP 2xx alone is
 * transport success — a 200 body carrying `ok=false` must fail the flow and keep
 * caches/toasts untouched.
 */
object ActionOutcome {

    /** Throws [ActionRejected] when the payload explicitly reports failure. */
    fun requireOk(response: JsonElement, fallbackMessage: String = "The server rejected the action.") {
        val obj = response as? JsonObject ?: return
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: return
        if (!ok) {
            val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            throw ActionRejected(detail ?: fallbackMessage)
        }
    }

    /** Non-throwing variant: null = accepted/unknown, non-null = user-facing reason. */
    fun rejectionReason(response: JsonElement, fallbackMessage: String = "The server rejected the action."): String? =
        try {
            requireOk(response, fallbackMessage)
            null
        } catch (rejected: ActionRejected) {
            rejected.message
        }

    class ActionRejected(message: String) : IllegalStateException(message)
}
