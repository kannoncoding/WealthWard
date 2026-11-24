package io.github.kannoncoding.wealthward.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountEntity(@PrimaryKey val id: String, val name: String, val currency: String, val kind: String, val openingMinor: Long, val creditLimitMinor: Long = 0)
@Entity(tableName = "transactions", indices = [Index("periodId")])
data class TransactionEntity(@PrimaryKey val id: String, val occurredAt: String, val businessDate: String, val type: String, val confirmed: Boolean, val periodId: String?, val expenseMinor: Long)
@Entity(tableName = "postings", foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("transactionId"), Index("accountId")])
data class PostingEntity(@PrimaryKey val id: String, val transactionId: String, val accountId: String, val minor: Long, val currency: String)
@Entity(tableName = "finance_periods")
data class FinancePeriodEntity(@PrimaryKey val id: String, val startsOn: String, val endsOn: String)
@Entity(tableName = "plan_versions", foreignKeys = [ForeignKey(entity = FinancePeriodEntity::class, parentColumns = ["id"], childColumns = ["periodId"], onDelete = ForeignKey.CASCADE)], indices = [Index("periodId")])
data class PlanVersionEntity(@PrimaryKey val id: String, val periodId: String, val version: Int)
@Entity(tableName = "plan_lines", foreignKeys = [ForeignKey(entity = PlanVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId")])
data class PlanLineEntity(@PrimaryKey val id: String, val versionId: String, val visualGroup: String, val financialType: String, val importSource: String, val minor: Long, val currency: String)
@Entity(tableName = "allocations", foreignKeys = [ForeignKey(entity = PostingEntity::class, parentColumns = ["id"], childColumns = ["postingId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PlanLineEntity::class, parentColumns = ["id"], childColumns = ["planLineId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("postingId"), Index("planLineId")])
data class AllocationEntity(@PrimaryKey val id: String, val postingId: String, val planLineId: String, val minor: Long)
@Entity(tableName = "credit_associations", foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class CreditAssociationEntity(@PrimaryKey val id: String, val facilityId: String, val accountId: String, val currency: String)

@Entity(tableName = "workspace_state")
data class WorkspaceStateEntity(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "workspace_requests")
data class WorkspaceRequestEntity(@PrimaryKey val id: String, val fingerprint: String, val result: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM workspace_state WHERE id='main'") suspend fun smallWorkspaceState(): WorkspaceStateEntity?
    @Query("SELECT length(CAST(payload AS BLOB)) FROM workspace_state WHERE id='main'") suspend fun workspaceByteCount(): Int?
    @Query("SELECT substr(CAST(payload AS BLOB), :start, :count) FROM workspace_state WHERE id='main'")
    suspend fun workspaceChunk(start: Int, count: Int): ByteArray
    @Query("SELECT id FROM workspace_state WHERE id='main'") fun observeWorkspace(): Flow<String?>

    @Transaction
    suspend fun workspaceState(): WorkspaceStateEntity? {
        val size = workspaceByteCount() ?: return null
        val chunkSize = 256 * 1024
        if (size <= chunkSize) return smallWorkspaceState()
        // A whole row can exceed Android's CursorWindow. Decode once so a chunk may bisect UTF-8.
        val payload = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = minOf(chunkSize, size - offset)
            val chunk = workspaceChunk(offset + 1, count)
            check(chunk.size == count)
            chunk.copyInto(payload, offset)
            offset += count
        }
        return WorkspaceStateEntity("main", payload.toString(Charsets.UTF_8))
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWorkspace(value: WorkspaceStateEntity)
    @Query("SELECT * FROM workspace_requests WHERE id=:id") suspend fun request(id: String): WorkspaceRequestEntity?
    @Query("SELECT * FROM workspace_requests ORDER BY id") suspend fun requests(): List<WorkspaceRequestEntity>
    @Insert suspend fun insertRequest(value: WorkspaceRequestEntity)
    @Query("DELETE FROM workspace_requests") suspend fun clearRequests()
    @Query("DELETE FROM workspace_state WHERE id='main'") suspend fun clearWorkspace()
    @Query("SELECT payload FROM workspace_state WHERE id='projection_limits'") suspend fun projectionLimits(): String?
    @Query("SELECT payload FROM workspace_state WHERE id='projection_limits'") fun observeProjectionLimits(): Flow<String?>

    @Query("SELECT * FROM accounts ORDER BY id") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM accounts ORDER BY id") fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM transactions ORDER BY id") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM postings ORDER BY id") suspend fun postings(): List<PostingEntity>
    @Query("SELECT * FROM finance_periods ORDER BY id") suspend fun periods(): List<FinancePeriodEntity>
    @Query("SELECT * FROM plan_versions ORDER BY id") suspend fun allVersions(): List<PlanVersionEntity>
    @Query("SELECT * FROM plan_lines ORDER BY id") suspend fun allPlanLines(): List<PlanLineEntity>
    @Query("SELECT * FROM allocations ORDER BY id") suspend fun allocations(): List<AllocationEntity>
    @Query("SELECT * FROM credit_associations ORDER BY id") suspend fun creditAssociations(): List<CreditAssociationEntity>
    @Query("SELECT COALESCE(SUM(p.minor),0) FROM postings p JOIN transactions t ON t.id=p.transactionId WHERE p.accountId=:id AND t.confirmed=1") suspend fun postedBalance(id: String): Long
    @Query("SELECT COUNT(*) FROM postings") fun observePostingCount(): Flow<Int>
    @Query("UPDATE accounts SET name=:name WHERE id=:id") suspend fun renameAccount(id: String, name: String)
    @Update suspend fun updateAccount(value: AccountEntity)
    @Query("UPDATE accounts SET openingMinor=:minor WHERE id=:id") suspend fun setOpening(id: String, minor: Long)
    @Insert suspend fun insertAccount(value: AccountEntity)
    @Insert suspend fun insertTransaction(value: TransactionEntity)
    @Insert suspend fun insertPostings(value: List<PostingEntity>)
    @Insert suspend fun insertPeriod(value: FinancePeriodEntity)
    @Insert suspend fun insertPlanVersion(value: PlanVersionEntity)
    @Insert suspend fun insertPlanLine(value: PlanLineEntity)

    @Insert suspend fun insertAllocation(value: AllocationEntity)
    @Insert suspend fun insertCreditAssociation(value: CreditAssociationEntity)
    @Query("SELECT * FROM finance_periods WHERE id=:id") suspend fun period(id: String): FinancePeriodEntity?
    @Query("SELECT * FROM plan_versions WHERE periodId=:periodId ORDER BY version") suspend fun versions(periodId: String): List<PlanVersionEntity>
    @Query("SELECT * FROM plan_lines WHERE versionId=:versionId ORDER BY id") suspend fun planLines(versionId: String): List<PlanLineEntity>
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM allocations") suspend fun clearAllocations()
    @Query("DELETE FROM credit_associations") suspend fun clearCreditAssociations()
    @Query("DELETE FROM plan_lines") suspend fun clearPlanLines()
    @Query("DELETE FROM plan_versions") suspend fun clearPlanVersions()
    @Query("DELETE FROM finance_periods") suspend fun clearPeriods()
}

@Database(entities = [AccountEntity::class, TransactionEntity::class, PostingEntity::class, FinancePeriodEntity::class, PlanVersionEntity::class, PlanLineEntity::class, AllocationEntity::class, CreditAssociationEntity::class, WorkspaceStateEntity::class, WorkspaceRequestEntity::class], version = 3, exportSchema = true)
abstract class WealthDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS allocations (id TEXT NOT NULL, postingId TEXT NOT NULL, planLineId TEXT NOT NULL, minor INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(postingId) REFERENCES postings(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(planLineId) REFERENCES plan_lines(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_allocations_postingId ON allocations(postingId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_allocations_planLineId ON allocations(planLineId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS workspace_state (id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS workspace_requests (id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(id))")
            }
        }
        fun open(context: Context): WealthDatabase = Room.databaseBuilder(context.applicationContext, WealthDatabase::class.java, "wealthward.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}


