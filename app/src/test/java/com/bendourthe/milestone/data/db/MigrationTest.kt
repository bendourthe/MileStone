package com.bendourthe.milestone.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath("migration-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = MileStoneDatabase::class,
    )

    @Test
    fun version2ClearsManualBalancesAndRenamesManualOnly() {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO loyalty_accounts (programId, balance, balanceOrigin, lastUpdatedEpochMs, lastManualEditEpochMs, " +
                    "lastAttemptEpochMs, consecutiveFailures, sortOrder, status, lastErrorCode, addedEpochMs) VALUES " +
                    "('hilton', 82410, 'MANUAL', 5, 5, NULL, 0, 0, 'MANUAL_ONLY', NULL, 1), " +
                    "('amex', 142500, 'SCRAPED', 7, NULL, 7, 0, 1, 'CONNECTED', NULL, 1)",
            )
            execSQL(
                "INSERT INTO balance_snapshots (programId, balance, origin, epochMs) VALUES " +
                    "('hilton', 82410, 'MANUAL', 5), ('amex', 142500, 'SCRAPED', 7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(2)

        assertEquals(
            listOf(listOf("hilton", null, null, "UNSUPPORTED"), listOf("amex", "142500", "SCRAPED", "CONNECTED")),
            db.rows("SELECT programId, balance, balanceOrigin, status FROM loyalty_accounts ORDER BY sortOrder", 4),
        )
        assertEquals(listOf(listOf("amex")), db.rows("SELECT programId FROM balance_snapshots", 1))
        db.close()
    }

    private fun SQLiteConnection.rows(sql: String, columns: Int): List<List<String?>> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) add((0 until columns).map { if (statement.isNull(it)) null else statement.getText(it) })
        }
    }
}
