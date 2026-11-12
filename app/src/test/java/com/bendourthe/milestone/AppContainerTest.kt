package com.bendourthe.milestone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    private val app = ApplicationProvider.getApplicationContext<MileStoneApplication>()

    @Test
    fun applicationContainerStartsWithNoAccounts() = runBlocking {
        val container = app.container

        assertTrue(container.accounts.observeAccounts().first().isEmpty())
        assertEquals(14, container.catalog.programs.size)
        assertSame(container, app.container)
        container.database.close()
    }
}
