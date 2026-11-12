package com.bendourthe.milestone.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bendourthe.milestone.data.settings.DataStoreSettingsRepository
import com.bendourthe.milestone.domain.SortMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(scope: TestScope, name: String = "settings") =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { tmp.root.resolve("$name.preferences_pb") })

    @Test
    fun defaultsToByCategoryAndPersists() = runTest(UnconfinedTestDispatcher()) {
        val repo = repo(this)
        assertEquals(SortMode.BY_CATEGORY, repo.sortMode.first())
        repo.setSortMode(SortMode.CUSTOM)
        assertEquals(SortMode.CUSTOM, repo.sortMode.first())
    }

    @Test
    fun corruptFileFallsBackToByCategory() = runTest(UnconfinedTestDispatcher()) {
        tmp.root.resolve("broken.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42))
        assertEquals(SortMode.BY_CATEGORY, repo(this, "broken").sortMode.first())
    }
}
