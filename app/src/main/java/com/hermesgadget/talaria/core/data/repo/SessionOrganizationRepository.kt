/*
 * Copyright 2026 Talaria contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hermesgadget.talaria.core.data.repo

import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionEntity
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionKind
import com.hermesgadget.talaria.core.data.db.LocalSessionCollectionLinkEntity
import com.hermesgadget.talaria.core.data.db.LocalSessionFavoriteEntity
import com.hermesgadget.talaria.core.data.db.SavedSessionFilterEntity
import com.hermesgadget.talaria.core.data.db.SessionOrganizationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class LocalSessionCollection(
    val id: Long,
    val connectionId: String,
    val name: String,
    val kind: LocalSessionCollectionKind,
)

data class SavedSessionFilter(
    val id: Long = 0,
    val connectionId: String,
    val name: String,
    val source: String? = null,
    val platform: String? = null,
    val endReason: String? = null,
    val labelId: Long? = null,
    val groupId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SessionOrganizationSnapshot(
    val collections: List<LocalSessionCollection> = emptyList(),
    val collectionIdsBySession: Map<String, Set<Long>> = emptyMap(),
    val favoriteSessionIds: Set<String> = emptySet(),
    val savedFilters: List<SavedSessionFilter> = emptyList(),
) {
    val labels: List<LocalSessionCollection>
        get() = collections.filter { it.kind == LocalSessionCollectionKind.LABEL }

    val groups: List<LocalSessionCollection>
        get() = collections.filter { it.kind == LocalSessionCollectionKind.GROUP }

    fun collectionIdsFor(sessionId: String): Set<Long> =
        collectionIdsBySession[sessionId].orEmpty()
}

/**
 * Room-backed local organization store. The connection id is the same
 * connection/profile scope used by [CachedSessionEntity] and [SessionDao].
 */
interface SessionOrganizationStore {
    fun observe(connectionId: String): Flow<SessionOrganizationSnapshot>

    suspend fun createCollection(
        connectionId: String,
        name: String,
        kind: LocalSessionCollectionKind,
    ): Long

    suspend fun deleteCollection(connectionId: String, collectionId: Long)

    suspend fun setCollectionMembership(
        connectionId: String,
        sessionId: String,
        collectionId: Long,
        assigned: Boolean,
    )

    suspend fun setFavorite(connectionId: String, sessionId: String, favorite: Boolean)

    suspend fun saveFilter(filter: SavedSessionFilter): Long

    suspend fun deleteFilter(connectionId: String, filterId: Long)
}

class SessionOrganizationRepository(
    private val dao: SessionOrganizationDao,
) : SessionOrganizationStore {
    override fun observe(connectionId: String): Flow<SessionOrganizationSnapshot> =
        combine(
            dao.observeCollections(connectionId),
            dao.observeCollectionLinks(connectionId),
            dao.observeFavorites(connectionId),
            dao.observeSavedFilters(connectionId),
        ) { collections, links, favorites, filters ->
            SessionOrganizationSnapshot(
                collections = collections.map(::toCollection),
                collectionIdsBySession = links.groupBy { it.sessionId }
                    .mapValues { (_, rows) -> rows.mapTo(linkedSetOf()) { it.collectionId } },
                favoriteSessionIds = favorites.mapTo(linkedSetOf()) { it.sessionId },
                savedFilters = filters.map(::toFilter),
            )
        }

    override suspend fun createCollection(
        connectionId: String,
        name: String,
        kind: LocalSessionCollectionKind,
    ): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "A local label or group needs a name" }
        val id = dao.insertCollection(
            LocalSessionCollectionEntity(
                connectionId = connectionId,
                name = normalizedName,
                kind = kind.name,
            ),
        )
        check(id != -1L) {
            "A local " + kind.name.lowercase() + " with that name already exists"
        }
        return id
    }

    override suspend fun deleteCollection(connectionId: String, collectionId: Long) {
        dao.deleteCollection(connectionId, collectionId)
    }

    override suspend fun setCollectionMembership(
        connectionId: String,
        sessionId: String,
        collectionId: Long,
        assigned: Boolean,
    ) {
        if (assigned) {
            dao.addCollectionLink(
                LocalSessionCollectionLinkEntity(
                    connectionId = connectionId,
                    sessionId = sessionId,
                    collectionId = collectionId,
                ),
            )
        } else {
            dao.deleteCollectionLink(connectionId, sessionId, collectionId)
        }
    }

    override suspend fun setFavorite(connectionId: String, sessionId: String, favorite: Boolean) {
        if (favorite) {
            dao.addFavorite(
                LocalSessionFavoriteEntity(
                    connectionId = connectionId,
                    sessionId = sessionId,
                ),
            )
        } else {
            dao.deleteFavorite(connectionId, sessionId)
        }
    }

    override suspend fun saveFilter(filter: SavedSessionFilter): Long {
        val normalized = filter.copy(
            name = filter.name.trim(),
            source = filter.source.normalizedOrNull(),
            platform = filter.platform.normalizedOrNull(),
            endReason = filter.endReason.normalizedOrNull(),
            updatedAt = System.currentTimeMillis(),
        )
        require(normalized.name.isNotEmpty()) { "A saved filter needs a name" }
        return dao.upsertSavedFilter(toEntity(normalized))
    }

    override suspend fun deleteFilter(connectionId: String, filterId: Long) {
        dao.deleteSavedFilter(connectionId, filterId)
    }

    private fun toCollection(entity: LocalSessionCollectionEntity): LocalSessionCollection =
        LocalSessionCollection(
            id = entity.id,
            connectionId = entity.connectionId,
            name = entity.name,
            kind = LocalSessionCollectionKind.valueOf(entity.kind),
        )

    private fun toFilter(entity: SavedSessionFilterEntity): SavedSessionFilter =
        SavedSessionFilter(
            id = entity.id,
            connectionId = entity.connectionId,
            name = entity.name,
            source = entity.source,
            platform = entity.platform,
            endReason = entity.endReason,
            labelId = entity.labelId,
            groupId = entity.groupId,
            updatedAt = entity.updatedAt,
        )

    private fun toEntity(filter: SavedSessionFilter): SavedSessionFilterEntity =
        SavedSessionFilterEntity(
            id = filter.id,
            connectionId = filter.connectionId,
            name = filter.name,
            source = filter.source,
            platform = filter.platform,
            endReason = filter.endReason,
            labelId = filter.labelId,
            groupId = filter.groupId,
            updatedAt = filter.updatedAt,
        )

    private fun String?.normalizedOrNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }
}
