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

    @Test
    fun version5KeepsEveryCardBenefitAndReminderUnderTheSameKeyAndAddsEmptyConnectionTables() {
        helper.createDatabase(4).apply {
            execSQL("INSERT INTO loyalty_accounts (programId, consecutiveFailures, sortOrder, status, addedEpochMs) VALUES ('amex', 0, 0, 'CONNECTED', 1), ('chase', 0, 1, 'CONNECTED', 2)")
            execSQL(
                "INSERT INTO cards (programId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs, benefitsReadGeneration) VALUES " +
                    "('amex', '1234', 'Synthetic Platinum', 0, 10, 11, 3), ('amex', '54321', 'Synthetic Gold', 1, 10, NULL, 0)",
            )
            execSQL(
                "INSERT INTO benefits (stableKey, programId, lastDigits, section, position, title, earnedCents, remainingCents, targetCents, " +
                    "periodLabel, periodEndEpochDay, periodLength, completed, description, enrollment) VALUES " +
                    "('k1', 'amex', '1234', 'CREDIT_EARNED', 0, 'Synthetic credit', 500, 1000, 1500, 'Monthly', 20100, 'MONTHLY', 0, NULL, NULL), " +
                    "('k2', 'amex', '1234', 'LISTED', 1, 'Synthetic listed', NULL, NULL, NULL, NULL, NULL, NULL, 0, 'Synthetic description', 'ENROLLED'), " +
                    "('k3', 'amex', '54321', 'COMPLETED', 0, 'Synthetic used', 2000, 0, 2000, NULL, NULL, NULL, 1, NULL, NULL)",
            )
            execSQL("INSERT INTO benefit_reminders (benefitKey, periodEndEpochDay, programId, lastDigits, sentEpochMs) VALUES ('k1', 20100, 'amex', '1234', 12)")
            close()
        }

        val db = helper.runMigrationsAndValidate(5, listOf(com.bendourthe.milestone.data.db.MIGRATION_4_5))

        assertEquals(
            listOf(
                listOf("amex", "1234", "1234", "Synthetic Platinum", "0", "10", "11", "3", null, null),
                listOf("amex", "54321", "54321", "Synthetic Gold", "1", "10", null, "0", null, null),
            ),
            db.rows(
                "SELECT programId, cardId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs, benefitsReadGeneration, " +
                    "segment, segmentOverride FROM cards ORDER BY sortOrder",
                10,
            ),
        )
        assertEquals(
            listOf(
                listOf("k1", "1234", "CREDIT_EARNED", "500", "1000", "1500", "Monthly", "20100", "MONTHLY", "0", null, null),
                listOf("k2", "1234", "LISTED", null, null, null, null, null, null, "0", "Synthetic description", "ENROLLED"),
                listOf("k3", "54321", "COMPLETED", "2000", "0", "2000", null, null, null, "1", null, null),
            ),
            db.rows(
                "SELECT stableKey, cardId, section, earnedCents, remainingCents, targetCents, periodLabel, periodEndEpochDay, periodLength, " +
                    "completed, description, enrollment FROM benefits ORDER BY stableKey",
                12,
            ),
        )
        assertEquals(listOf(listOf("k1", "20100", "amex", "1234", "12")), db.rows("SELECT * FROM benefit_reminders", 5))
        listOf("issuer_connections", "card_coverage").forEach { table ->
            assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM $table", 1))
        }
        // Cascades still work on the rebuilt tables: removing a card removes its benefits and reminders only.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM cards WHERE cardId = '1234'")
        assertEquals(listOf(listOf("k3")), db.rows("SELECT stableKey FROM benefits", 1))
        assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM benefit_reminders", 1))
        assertEquals(listOf(listOf("2")), db.rows("SELECT COUNT(*) FROM loyalty_accounts", 1))
        db.close()
    }

    private fun SQLiteConnection.rows(sql: String, columns: Int): List<List<String?>> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) add((0 until columns).map { if (statement.isNull(it)) null else statement.getText(it) })
        }
    }
}
