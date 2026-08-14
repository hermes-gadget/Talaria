/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.feature.chat

import com.hermesgadget.talaria.core.network.SidecarSlashCompletion
import com.hermesgadget.talaria.domain.model.SlashArgumentMode
import com.hermesgadget.talaria.domain.model.SlashCommand
import com.hermesgadget.talaria.domain.model.SlashCommands

/**
 * H3 slice: merges server slash completions with the local catalog.
 *
 * The ViewModel owns the RPC call and its generation guard; this class owns
 * the pure merge policy (catalog enrichment, argument mode inference,
 * de-duplication, cap) so it can be unit-tested without a socket.
 */
internal class ChatSlashCompleter(
    private val catalog: List<SlashCommand> = SlashCommands.defaults,
) {
    fun mergeRemote(
        completions: List<SidecarSlashCompletion>,
        limit: Int = MAX_SUGGESTIONS,
    ): List<SlashCommand> =
        completions.asSequence().map { completion ->
            val replacement = completion.replacement.trimEnd()
            val token = replacement.substringBefore(' ')
            val known = catalog.firstOrNull { it.command.equals(token, ignoreCase = true) }
            SlashCommand(
                command = replacement,
                description = completion.description.ifBlank { known?.description ?: "Hermes command" },
                category = known?.category ?: if (completion.kind == "skill") "Skills" else "Commands",
                aliases = known?.aliases.orEmpty(),
                argumentMode = known?.argumentMode ?: if (
                    completion.replacement.endsWith(' ') || replacement.contains(' ')
                ) {
                    SlashArgumentMode.MIXED
                } else {
                    SlashArgumentMode.NONE
                },
            )
        }
            .distinctBy { it.command.lowercase() }
            .take(limit)
            .toList()

    private companion object {
        const val MAX_SUGGESTIONS = 12
    }
}
