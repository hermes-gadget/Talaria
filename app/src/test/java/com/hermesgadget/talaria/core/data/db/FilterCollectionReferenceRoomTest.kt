/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for B03: deleting a label must clear only the filter's
 * label reference, not an unrelated still-valid group reference (and vice
 * versa). One filter can legitimately reference a label AND a group at the
 * same time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FilterCollectionReferenceRoomTest {
    private lateinit var database: TalariaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TalariaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingLabelKeepsGroupReference() = runTest {
        val dao = database.sessionOrganization()
        val label = dao.insertCollection(collection("c1", LocalSessionCollectionKind.LABEL))
        val group = dao.insertCollection(collection("c1", LocalSessionCollectionKind.GROUP))
        dao.upsertSavedFilter(
            SavedSessionFilterEntity(
                connectionId = "c1",
                name = "Both",
                labelId = label,
                groupId = group,
            ),
        )

        dao.clearFilterCollectionReferences("c1", label)

        val filter = dao.observeSavedFilters("c1").first().single()
        assertNull("label reference must be cleared", filter.labelId)
        assertEquals("group reference must survive label deletion", group, filter.groupId)
    }

    @Test
    fun deletingGroupKeepsLabelReference() = runTest {
        val dao = database.sessionOrganization()
        val label = dao.insertCollection(collection("c2", LocalSessionCollectionKind.LABEL))
        val group = dao.insertCollection(collection("c2", LocalSessionCollectionKind.GROUP))
        dao.upsertSavedFilter(
            SavedSessionFilterEntity(
                connectionId = "c2",
                name = "Both",
                labelId = label,
                groupId = group,
            ),
        )

        dao.clearFilterCollectionReferences("c2", group)

        val filter = dao.observeSavedFilters("c2").first().single()
        assertEquals("label reference must survive group deletion", label, filter.labelId)
        assertNull("group reference must be cleared", filter.groupId)
    }

    @Test
    fun deletingNeitherReferencedCollectionLeavesFilterUntouched() = runTest {
        val dao = database.sessionOrganization()
        val label = dao.insertCollection(collection("c3", LocalSessionCollectionKind.LABEL))
        val group = dao.insertCollection(collection("c3", LocalSessionCollectionKind.GROUP))
        dao.upsertSavedFilter(
            SavedSessionFilterEntity(
                connectionId = "c3",
                name = "Both",
                labelId = label,
                groupId = group,
            ),
        )

        dao.clearFilterCollectionReferences("c3", 99999L)

        val filter = dao.observeSavedFilters("c3").first().single()
        assertEquals(label, filter.labelId)
        assertEquals(group, filter.groupId)
    }

    private fun collection(connectionId: String, kind: LocalSessionCollectionKind) =
        LocalSessionCollectionEntity(
            connectionId = connectionId,
            name = kind.name.lowercase(),
            kind = kind.name,
        )
}
