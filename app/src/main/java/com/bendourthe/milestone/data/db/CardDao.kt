package com.bendourthe.milestone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE programId = :programId ORDER BY sortOrder, lastDigits")
    fun observeCards(programId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY programId, sortOrder, lastDigits")
    suspend fun allCards(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE programId = :programId AND lastDigits = :lastDigits")
    suspend fun card(programId: String, lastDigits: String): CardEntity?

    @Query("SELECT lastDigits FROM cards WHERE programId = :programId")
    suspend fun lastDigits(programId: String): List<String>

    /** Updates in place on conflict, so the cascade to benefits and reminder records never fires. */
    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("DELETE FROM cards WHERE programId = :programId AND lastDigits IN (:lastDigits)")
    suspend fun deleteCards(programId: String, lastDigits: List<String>)

    @Query("DELETE FROM cards WHERE programId = :programId")
    suspend fun deleteProgramCards(programId: String)

    @Query("UPDATE cards SET benefitsReadEpochMs = :readEpochMs WHERE programId = :programId AND lastDigits = :lastDigits")
    suspend fun setBenefitsRead(programId: String, lastDigits: String, readEpochMs: Long)

    @Query(
        """
        SELECT * FROM benefits WHERE programId = :programId AND lastDigits = :lastDigits
        ORDER BY CASE section WHEN 'SPEND_PROGRESS' THEN 0 WHEN 'CREDIT_EARNED' THEN 1 WHEN 'COMPLETED' THEN 2 WHEN 'LISTED' THEN 4 ELSE 3 END,
            rowid
        """,
    )
    fun observeBenefits(programId: String, lastDigits: String): Flow<List<BenefitEntity>>

    @Query("SELECT * FROM benefits ORDER BY rowid")
    suspend fun allBenefits(): List<BenefitEntity>

    @Query("DELETE FROM benefits WHERE programId = :programId AND lastDigits = :lastDigits")
    suspend fun deleteBenefits(programId: String, lastDigits: String)

    @Query("DELETE FROM benefits WHERE programId = :programId AND lastDigits = :lastDigits AND section IN (:sections)")
    suspend fun deleteBenefitSections(programId: String, lastDigits: String, sections: List<String>)

    @Insert
    suspend fun insertBenefits(benefits: List<BenefitEntity>)

    @Query("SELECT COUNT(*) FROM benefit_reminders WHERE benefitKey = :benefitKey AND periodEndEpochDay = :periodEndEpochDay")
    suspend fun reminderCount(benefitKey: String, periodEndEpochDay: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReminder(record: ReminderRecordEntity): Long

    @Query("DELETE FROM benefit_reminders WHERE periodEndEpochDay < :todayEpochDay")
    suspend fun pruneReminders(todayEpochDay: Long)

    @Query("SELECT COUNT(*) FROM loyalty_accounts WHERE programId = :programId")
    suspend fun accountCount(programId: String): Int
}
