/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.chat

/**
 * N0.2 — exact, informed approval choices.
 *
 * The Hermes gateway offers a set of `choices` for approval prompts (e.g.
 * `once`, `always`, `deny`). Historically the app submitted the first
 * non-`deny` server choice on a generic Approve tap, which could silently
 * grant `always` (or another broad/unknown authorization) when it was listed
 * first. This policy makes every grant explicit and fail-closed:
 *
 *  - no tapped choice  -> `null` (callers must NOT submit anything)
 *  - a tapped choice not offered by the server -> `null` (unknown/ambiguous)
 *  - `deny` -> `"deny"`
 *  - an offered choice tapped explicitly -> that exact value
 *  - no server choices at all -> only the safe one-shot value is accepted
 *
 * Broad choices (anything beyond the safe one-shot set) still require an
 * explicit confirmation step in the UI (see [requiresExplicitBroadConfirm]).
 */
object ApprovalChoicePolicy {

    /** Choices treated as safe, single-shot grants. */
    val SAFE_ONESHOT_CHOICES: Set<String> = setOf("once")

    /** Resolve the exact choice to submit, or `null` to fail closed. */
    fun resolveChoice(serverChoices: List<String>, tapped: String?): String? {
        val choice = tapped?.trim()?.lowercase().orEmpty()
        if (choice.isEmpty()) return null
        if (choice == "deny") return "deny"

        val offered = serverChoices
            .mapNotNull { it.trim().lowercase().takeIf { c -> c.isNotEmpty() && c != "deny" } }
            .toSet()

        return when {
            choice in offered -> choice
            offered.isEmpty() && choice in SAFE_ONESHOT_CHOICES -> choice
            else -> null
        }
    }

    /** True when submitting [choice] must go through an explicit broad-confirm step. */
    fun requiresExplicitBroadConfirm(choice: String): Boolean =
        choice.trim().lowercase() !in SAFE_ONESHOT_CHOICES && choice.trim().lowercase() != "deny"
}
