package io.github.kannoncoding.wealthward.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F05MigrationTest {
 @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),WealthDatabase::class.java.canonicalName!!,FrameworkSQLiteOpenHelperFactory())
 @Test fun populatedTwoToThreePreservesLedgerAndCreatesDurableRequestBoundary() {
  helper.createDatabase("f05-2-3.db",2).apply {
   execSQL("INSERT INTO accounts VALUES ('a','Generic account','CRC','CASH',50000000,0)")
   execSQL("INSERT INTO transactions VALUES ('expense','2026-11-01T18:00:00Z','2026-11-01','EXPENSE',1,NULL,1500000)")
   execSQL("INSERT INTO postings VALUES ('expense:0','expense','a',-1500000,'CRC')")
   close()
  }
  helper.runMigrationsAndValidate("f05-2-3.db",3,true,WealthDatabase.MIGRATION_2_3).apply {
   query("SELECT a.openingMinor+p.minor FROM accounts a JOIN postings p ON p.accountId=a.id").use { it.moveToFirst();assertEquals(48500000,it.getLong(0)) }
   query("SELECT COUNT(*) FROM workspace_requests").use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
   close()
  }
 }
 @Test fun populatedOneToThreePreservesUnknownOpeningDateWithoutInventingMetadata() {
  helper.createDatabase("f05-1-3.db",1).apply {
   execSQL("INSERT INTO accounts VALUES ('legacy','Generic legacy account','USD','CASH',12345,0)")
   close()
  }
  helper.runMigrationsAndValidate("f05-1-3.db",3,true,WealthDatabase.MIGRATION_1_2,WealthDatabase.MIGRATION_2_3).apply {
   query("SELECT openingMinor FROM accounts WHERE id='legacy'").use { it.moveToFirst();assertEquals(12345,it.getLong(0)) }
   query("SELECT COUNT(*) FROM workspace_state").use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
   query("PRAGMA foreign_key_list(allocations)").use { assertEquals(2,it.count) }
   close()
  }
 }
}
