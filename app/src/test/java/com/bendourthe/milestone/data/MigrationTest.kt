package com.bendourthe.milestone.data

import androidx.room.testing.MigrationTestHelper
import com.bendourthe.milestone.data.db.MileStoneDatabase
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

    @Test
    fun version3KeepsAccountsAndSnapshotsAndAddsEmptyBenefitTables() {
        helper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO loyalty_accounts (programId, balance, balanceOrigin, lastUpdatedEpochMs, " +
                    "lastAttemptEpochMs, consecutiveFailures, sortOrder, status, lastErrorCode, addedEpochMs) VALUES " +
                    "('amex', 142500, 'SCRAPED', 7, 7, 0, 0, 'CONNECTED', NULL, 1), " +
                    "('delta', NULL, NULL, NULL, 9, 2, 1, 'ERROR', 'TIMEOUT', 3)",
            )
            execSQL(
                "INSERT INTO balance_snapshots (programId, balance, origin, epochMs) VALUES " +
                    "('amex', 140000, 'SCRAPED', 5), ('amex', 142500, 'SCRAPED', 7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(3)

        assertEquals(
            listOf(
                listOf("amex", "142500", "SCRAPED", "7", "7", "0", "0", "CONNECTED", null, "1"),
                listOf("delta", null, null, null, "9", "2", "1", "ERROR", "TIMEOUT", "3"),
            ),
            db.rows(
                "SELECT programId, balance, balanceOrigin, lastUpdatedEpochMs, lastAttemptEpochMs, consecutiveFailures, " +
                    "sortOrder, status, lastErrorCode, addedEpochMs FROM loyalty_accounts ORDER BY sortOrder",
                10,
            ),
        )
        assertEquals(
            listOf(listOf("amex", "140000", "5"), listOf("amex", "142500", "7")),
            db.rows("SELECT programId, balance, epochMs FROM balance_snapshots ORDER BY epochMs", 3),
        )
        listOf("cards", "benefits", "benefit_reminders").forEach { table ->
            assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM $table", 1))
        }
        db.close()
    }

    @Test
    fun version4PreservesBenefitsEnrollmentAndRemindersAndStartsReadGenerationAtZero() {
        helper.createDatabase(3).apply {
            execSQL("INSERT INTO loyalty_accounts (programId, consecutiveFailures, sortOrder, status, addedEpochMs) VALUES ('amex', 0, 0, 'CONNECTED', 1)")
            execSQL("INSERT INTO cards (programId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs) VALUES ('amex', '1234', 'Synthetic Platinum', 0, 10, 11)")
            execSQL("INSERT INTO benefits (stableKey, programId, lastDigits, section, position, title, completed, description, enrollment) VALUES ('synthetic-credit', 'amex', '1234', 'LISTED', 0, 'Synthetic benefit', 0, 'Sample description', 'ENROLLMENT_REQUIRED')")
            execSQL("INSERT INTO benefit_reminders (benefitKey, periodEndEpochDay, programId, lastDigits, sentEpochMs) VALUES ('synthetic-credit', 20000, 'amex', '1234', 12)")
            close()
        }
        val db = helper.runMigrationsAndValidate(4)
        assertEquals(listOf(listOf("11", "0")), db.rows("SELECT benefitsReadEpochMs, benefitsReadGeneration FROM cards", 2))
        assertEquals(listOf(listOf("Synthetic benefit", "Sample description", "ENROLLMENT_REQUIRED")), db.rows("SELECT title, description, enrollment FROM benefits", 3))
        assertEquals(listOf(listOf("synthetic-credit", "12")), db.rows("SELECT benefitKey, sentEpochMs FROM benefit_reminders", 2))
        assertEquals(listOf(listOf("CONNECTED")), db.rows("SELECT status FROM loyalty_accounts", 1))
        db.close()
    }

    private fun SQLiteConnection.rows(sql: String, columns: Int): List<List<String?>> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) add((0 until columns).map { if (statement.isNull(it)) null else statement.getText(it) })
        }
    }
}
