package com.bendourthe.milestone.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LoyaltyAccountEntity::class,
        BalanceSnapshotEntity::class,
        CardEntity::class,
        BenefitEntity::class,
        ReminderRecordEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = MileStoneDatabase.RemoveManualEntry::class),
        // Version 3 adds the cards, benefits, and benefit_reminders tables; v0.1 tables are untouched.
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class MileStoneDatabase : RoomDatabase() {
    abstract fun loyaltyDao(): LoyaltyDao

    abstract fun cardDao(): CardDao

    /** Version 2 drops manual entry (ADR-0003): hand-typed balances and their history rows are cleared. */
    @DeleteColumn(tableName = "loyalty_accounts", columnName = "lastManualEditEpochMs")
    class RemoveManualEntry : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM balance_snapshots WHERE origin = 'MANUAL'")
            db.execSQL(
                "UPDATE loyalty_accounts SET balance = NULL, balanceOrigin = NULL, lastUpdatedEpochMs = NULL WHERE balanceOrigin = 'MANUAL'",
            )
            db.execSQL("UPDATE loyalty_accounts SET status = 'UNSUPPORTED' WHERE status = 'MANUAL_ONLY'")
        }
    }

    companion object {
        const val NAME = "milestone.db"

        fun create(context: Context): MileStoneDatabase =
            Room.databaseBuilder(context, MileStoneDatabase::class.java, NAME).build()
    }
}
