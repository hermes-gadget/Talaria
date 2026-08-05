/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.data.db

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionOrganizationMigrationTest {
    @Test
    fun migrationFromV3PreservesCachedRowsAndCreatesLocalTables() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext(),
            )
                .name(null)
                .callback(V3Callback())
                .build(),
        )
        val database = helper.writableDatabase
        database.execSQL(
            """
            INSERT INTO cached_sessions
                (id, connectionId, title, source, model, preview, messageCount, lastActive, json, updatedAt)
            VALUES ('session-1', 'scope-a', 'Old', 'cli', 'model', 'preview', 1, 'now', '{}', 1)
            """.trimIndent(),
        )

        TalariaDatabase.MIGRATION_3_4.migrate(database)

        database.query(
            "SELECT title, platform FROM cached_sessions WHERE connectionId = 'scope-a' AND id = 'session-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Old", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("platform")))
        }
        val tables = buildSet {
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
        assertTrue(tables.contains("local_session_collections"))
        assertTrue(tables.contains("local_session_collection_links"))
        assertTrue(tables.contains("local_session_favorites"))
        assertTrue(tables.contains("saved_session_filters"))
        helper.close()
    }

    private class V3Callback : SupportSQLiteOpenHelper.Callback(3) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE cached_sessions (
                    id TEXT NOT NULL,
                    connectionId TEXT NOT NULL,
                    title TEXT,
                    source TEXT,
                    model TEXT,
                    preview TEXT,
                    messageCount INTEGER,
                    lastActive TEXT,
                    json TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(connectionId, id)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
