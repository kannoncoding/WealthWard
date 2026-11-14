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
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WealthDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Test fun migrationPreservesOpeningBalancesAndCreatesAllocationConstraints() {
        helper.createDatabase("migration-test.db", 1).apply {
            execSQL("INSERT INTO accounts(id,name,currency,kind,openingMinor,creditLimitMinor) VALUES ('a','Account A','CRC','CASH',12345,0)")
            close()
        }
        helper.runMigrationsAndValidate("migration-test.db", 2, true, WealthDatabase.MIGRATION_1_2).apply {
            query("SELECT openingMinor FROM accounts WHERE id='a'").use { it.moveToFirst(); assertEquals(12345L, it.getLong(0)) }
            query("PRAGMA foreign_key_list(allocations)").use { assertEquals(2, it.count) }
            close()
        }
    }
}
