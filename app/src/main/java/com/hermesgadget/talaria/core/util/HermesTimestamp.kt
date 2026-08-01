/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hermesgadget.talaria.core.util

import java.text.DateFormat
import java.util.Date

/** Render both current epoch timestamps and legacy ISO timestamps without leaking raw epoch values. */
fun formatHermesTimestamp(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val numeric = text.toDoubleOrNull()
    if (numeric != null && numeric.isFinite()) {
        val millis = if (numeric >= 100_000_000_000.0) numeric.toLong() else (numeric * 1_000.0).toLong()
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
    }
    return text.replace('T', ' ').removeSuffix("Z").substringBefore('.')
}
