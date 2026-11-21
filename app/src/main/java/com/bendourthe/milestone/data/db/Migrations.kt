package com.bendourthe.milestone.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 5 (ADR-0009): cards are keyed by a local card id. Every existing card keeps its last digits as its id,
 * so benefits, reminder records, benefit keys, and display preferences keep their keys byte for byte. Adds the
 * display last digits, the derived and owner-chosen segment, and the issuer connection and coverage tables.
 * Tables are rebuilt because primary and foreign key columns change; every row is copied.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cards_new` (`programId` TEXT NOT NULL, `cardId` TEXT NOT NULL, `lastDigits` TEXT, " +
                "`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `cardListReadEpochMs` INTEGER, `benefitsReadEpochMs` INTEGER, " +
                "`benefitsReadGeneration` INTEGER NOT NULL DEFAULT 0, `segment` TEXT, `segmentOverride` TEXT, " +
                "PRIMARY KEY(`programId`, `cardId`), FOREIGN KEY(`programId`) REFERENCES `loyalty_accounts`(`programId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `cards_new` (`programId`, `cardId`, `lastDigits`, `name`, `sortOrder`, `cardListReadEpochMs`, " +
                "`benefitsReadEpochMs`, `benefitsReadGeneration`, `segment`, `segmentOverride`) " +
                "SELECT `programId`, `lastDigits`, `lastDigits`, `name`, `sortOrder`, `cardListReadEpochMs`, `benefitsReadEpochMs`, " +
                "`benefitsReadGeneration`, NULL, NULL FROM `cards`",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `benefits_new` (`stableKey` TEXT NOT NULL, `programId` TEXT NOT NULL, `cardId` TEXT NOT NULL, " +
                "`section` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `earnedCents` INTEGER, `remainingCents` INTEGER, " +
                "`targetCents` INTEGER, `periodLabel` TEXT, `periodEndEpochDay` INTEGER, `periodLength` TEXT, `completed` INTEGER NOT NULL, " +
                "`description` TEXT, `enrollment` TEXT, PRIMARY KEY(`stableKey`), FOREIGN KEY(`programId`, `cardId`) " +
                "REFERENCES `cards`(`programId`, `cardId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `benefits_new` SELECT `stableKey`, `programId`, `lastDigits`, `section`, `position`, `title`, `earnedCents`, " +
                "`remainingCents`, `targetCents`, `periodLabel`, `periodEndEpochDay`, `periodLength`, `completed`, `description`, " +
                "`enrollment` FROM `benefits` ORDER BY rowid",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `benefit_reminders_new` (`benefitKey` TEXT NOT NULL, `periodEndEpochDay` INTEGER NOT NULL, " +
                "`programId` TEXT NOT NULL, `cardId` TEXT NOT NULL, `sentEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`benefitKey`, `periodEndEpochDay`), FOREIGN KEY(`programId`, `cardId`) " +
                "REFERENCES `cards`(`programId`, `cardId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `benefit_reminders_new` SELECT `benefitKey`, `periodEndEpochDay`, `programId`, `lastDigits`, `sentEpochMs` " +
                "FROM `benefit_reminders`",
        )
        // Children first, then the parent, so no cascade fires while the old tables are dropped.
        db.execSQL("DROP TABLE `benefit_reminders`")
        db.execSQL("DROP TABLE `benefits`")
        db.execSQL("DROP TABLE `cards`")
        db.execSQL("ALTER TABLE `cards_new` RENAME TO `cards`")
        db.execSQL("ALTER TABLE `benefits_new` RENAME TO `benefits`")
        db.execSQL("ALTER TABLE `benefit_reminders_new` RENAME TO `benefit_reminders`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_programId` ON `cards` (`programId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_benefits_programId_cardId` ON `benefits` (`programId`, `cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_benefit_reminders_programId_cardId` ON `benefit_reminders` (`programId`, `cardId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `issuer_connections` (`connectionId` TEXT NOT NULL, `programId` TEXT NOT NULL, " +
                "`segment` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `addedEpochMs` INTEGER NOT NULL, `inventoryReadEpochMs` INTEGER, " +
                "`inventoryComplete` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`connectionId`), FOREIGN KEY(`programId`) " +
                "REFERENCES `loyalty_accounts`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_issuer_connections_programId` ON `issuer_connections` (`programId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `card_coverage` (`connectionId` TEXT NOT NULL, `programId` TEXT NOT NULL, `cardId` TEXT NOT NULL, " +
                "`bindingHash` TEXT NOT NULL, `lastSeenEpochMs` INTEGER NOT NULL, PRIMARY KEY(`connectionId`, `programId`, `cardId`), " +
                "FOREIGN KEY(`connectionId`) REFERENCES `issuer_connections`(`connectionId`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`programId`, `cardId`) REFERENCES `cards`(`programId`, `cardId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_coverage_connectionId_bindingHash` ON `card_coverage` (`connectionId`, `bindingHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_coverage_programId_bindingHash` ON `card_coverage` (`programId`, `bindingHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_coverage_programId_cardId` ON `card_coverage` (`programId`, `cardId`)")
    }
}
