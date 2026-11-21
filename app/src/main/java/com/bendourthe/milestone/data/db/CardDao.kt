package com.bendourthe.milestone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE programId = :programId ORDER BY sortOrder, cardId")
    fun observeCards(programId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards ORDER BY programId, sortOrder, cardId")
    suspend fun allCards(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE programId = :programId AND cardId = :cardId")
    suspend fun card(programId: String, cardId: String): CardEntity?

    @Query("SELECT cardId FROM cards WHERE programId = :programId")
    suspend fun cardIds(programId: String): List<String>

    /** Legacy last-digits cards: the ones no connection reaches. Connection-managed cards are never pruned here. */
    @Query("SELECT cardId FROM cards WHERE programId = :programId AND NOT EXISTS (SELECT 1 FROM card_coverage cc WHERE cc.programId = cards.programId AND cc.cardId = cards.cardId)")
    suspend fun uncoveredCardIds(programId: String): List<String>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM cards WHERE programId = :programId")
    suspend fun maxSortOrder(programId: String): Int

    /** Updates in place on conflict, so the cascade to benefits and reminder records never fires. */
    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("DELETE FROM cards WHERE programId = :programId AND cardId IN (:cardIds)")
    suspend fun deleteCards(programId: String, cardIds: List<String>)

    @Query("DELETE FROM cards WHERE programId = :programId")
    suspend fun deleteProgramCards(programId: String)

    @Query("UPDATE cards SET benefitsReadEpochMs = :readEpochMs, benefitsReadGeneration = benefitsReadGeneration + 1 WHERE programId = :programId AND cardId = :cardId")
    suspend fun setBenefitsRead(programId: String, cardId: String, readEpochMs: Long)

    @Query("UPDATE cards SET segmentOverride = :segment WHERE programId = :programId AND cardId = :cardId")
    suspend fun setSegmentOverride(programId: String, cardId: String, segment: String?)

    @Query("UPDATE cards SET segment = :segment WHERE programId = :programId AND cardId = :cardId")
    suspend fun setSegment(programId: String, cardId: String, segment: String?)

    @Query(
        """
        SELECT * FROM benefits WHERE programId = :programId AND cardId = :cardId
        ORDER BY CASE section WHEN 'SPEND_PROGRESS' THEN 0 WHEN 'CREDIT_EARNED' THEN 1 WHEN 'COMPLETED' THEN 2 WHEN 'LISTED' THEN 4 ELSE 3 END,
            rowid
        """,
    )
    fun observeBenefits(programId: String, cardId: String): Flow<List<BenefitEntity>>

    @Query("SELECT * FROM benefits ORDER BY rowid")
    suspend fun allBenefits(): List<BenefitEntity>

    @Query("DELETE FROM benefits WHERE programId = :programId AND cardId = :cardId")
    suspend fun deleteBenefits(programId: String, cardId: String)

    @Query("DELETE FROM benefits WHERE programId = :programId AND cardId = :cardId AND section IN (:sections)")
    suspend fun deleteBenefitSections(programId: String, cardId: String, sections: List<String>)

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

    // Connections and coverage (ADR-0009).

    @Query("SELECT * FROM issuer_connections WHERE programId = :programId ORDER BY sortOrder, connectionId")
    fun observeConnections(programId: String): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM issuer_connections WHERE programId = :programId ORDER BY sortOrder, connectionId")
    suspend fun connections(programId: String): List<ConnectionEntity>

    @Query("SELECT * FROM issuer_connections WHERE connectionId = :connectionId")
    suspend fun connection(connectionId: String): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConnection(connection: ConnectionEntity)

    @Query("DELETE FROM issuer_connections WHERE connectionId = :connectionId")
    suspend fun deleteConnection(connectionId: String)

    @Query("UPDATE issuer_connections SET segment = :segment WHERE connectionId = :connectionId")
    suspend fun setConnectionSegment(connectionId: String, segment: String)

    @Query("UPDATE issuer_connections SET inventoryReadEpochMs = :readEpochMs, inventoryComplete = :complete WHERE connectionId = :connectionId")
    suspend fun setInventoryRead(connectionId: String, readEpochMs: Long, complete: Boolean)

    @Query("SELECT * FROM card_coverage WHERE connectionId = :connectionId")
    suspend fun coverageOf(connectionId: String): List<CardCoverageEntity>

    @Query("SELECT * FROM card_coverage WHERE programId = :programId AND bindingHash = :bindingHash")
    suspend fun coverageByBinding(programId: String, bindingHash: String): List<CardCoverageEntity>

    @Query("SELECT * FROM card_coverage WHERE programId = :programId AND cardId = :cardId")
    suspend fun coverageOfCard(programId: String, cardId: String): List<CardCoverageEntity>

    @Upsert
    suspend fun upsertCoverage(coverage: CardCoverageEntity)

    @Query("DELETE FROM card_coverage WHERE connectionId = :connectionId AND cardId IN (:cardIds)")
    suspend fun deleteCoverage(connectionId: String, cardIds: List<String>)

    @Query("SELECT segment FROM issuer_connections WHERE connectionId IN (SELECT connectionId FROM card_coverage WHERE programId = :programId AND cardId = :cardId)")
    suspend fun reachingSegments(programId: String, cardId: String): List<String>
}
