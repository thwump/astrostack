package com.astrostack.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CaptureSessionDao] using an in-memory Room database.
 *
 * These run on the device but require no camera, filesystem, or network access.
 */
@RunWith(AndroidJUnit4::class)
class CaptureSessionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: CaptureSessionDao

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun session(
        name: String = "Test Session",
        frameCount: Int = 10,
        iso: Int = 1600,
        exposureNs: Long = 4_000_000_000L,
        dir: String = "/data/test/session",
    ) = CaptureSession(name = name, frameCount = frameCount, iso = iso,
        exposureTimeNs = exposureNs, directoryPath = dir)

    private fun frame(sessionId: Long, index: Int) =
        CaptureFrame(sessionId = sessionId, frameIndex = index,
            filePath = "/data/test/session/frame_%03d.dng".format(index))

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Before fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.captureSessionDao()
    }

    @After fun closeDb() = database.close()

    // ─── Session CRUD ─────────────────────────────────────────────────────────

    @Test fun insertSession_returnsNonZeroId() = runTest {
        val id = dao.insertSession(session())
        assertTrue("Inserted ID should be > 0", id > 0)
    }

    @Test fun insertAndRetrieveSession_byId() = runTest {
        val id = dao.insertSession(session(name = "M42 Test", frameCount = 5, iso = 3200))
        val retrieved = dao.getSessionById(id)
        assertNotNull(retrieved)
        assertEquals("M42 Test", retrieved!!.name)
        assertEquals(5, retrieved.frameCount)
        assertEquals(3200, retrieved.iso)
    }

    @Test fun getSessionById_returnsNull_forMissingId() = runTest {
        assertNull(dao.getSessionById(9999L))
    }

    @Test fun getAllSessions_flowEmitsInsertedSessions() = runTest {
        dao.insertSession(session(name = "Session A"))
        dao.insertSession(session(name = "Session B"))
        val sessions = dao.getAllSessions().first()
        assertEquals(2, sessions.size)
    }

    @Test fun getAllSessions_orderedByCreatedAtDescending() = runTest {
        val id1 = dao.insertSession(session(name = "First"))
        // Small delay to ensure different timestamps (auto-assigned via System.currentTimeMillis)
        Thread.sleep(5)
        val id2 = dao.insertSession(session(name = "Second"))
        val sessions = dao.getAllSessions().first()
        // Most recent first
        assertEquals("Second", sessions[0].name)
        assertEquals("First",  sessions[1].name)
    }

    @Test fun deleteSession_removesItFromList() = runTest {
        val id = dao.insertSession(session(name = "ToDelete"))
        val toDelete = dao.getSessionById(id)!!
        dao.deleteSession(toDelete)
        assertNull(dao.getSessionById(id))
    }

    @Test fun updateSession_persistsChange() = runTest {
        val id = dao.insertSession(session(name = "Original"))
        val stored = dao.getSessionById(id)!!
        dao.updateSession(stored.copy(name = "Updated"))
        assertEquals("Updated", dao.getSessionById(id)!!.name)
    }

    @Test fun updateStackedResult_setsPathAndAlgorithm() = runTest {
        val id = dao.insertSession(session())
        dao.updateStackedResult(id, "/stacked/result.png", "SIGMA_CLIPPING")
        val s = dao.getSessionById(id)!!
        assertEquals("/stacked/result.png", s.stackedImagePath)
        assertEquals("SIGMA_CLIPPING", s.stackingAlgorithm)
    }

    @Test fun markCaptureComplete_setsFlag() = runTest {
        val id = dao.insertSession(session())
        dao.markCaptureComplete(id)
        assertTrue(dao.getSessionById(id)!!.isCaptureDone)
    }

    // ─── Frame CRUD ───────────────────────────────────────────────────────────

    @Test fun insertFrame_returnsNonZeroId() = runTest {
        val sessionId = dao.insertSession(session())
        val frameId = dao.insertFrame(frame(sessionId, 0))
        assertTrue(frameId > 0)
    }

    @Test fun getFramesForSession_returnsAllFramesInOrder() = runTest {
        val sessionId = dao.insertSession(session())
        dao.insertFrame(frame(sessionId, 2))
        dao.insertFrame(frame(sessionId, 0))
        dao.insertFrame(frame(sessionId, 1))
        val frames = dao.getFramesForSession(sessionId)
        assertEquals(3, frames.size)
        assertEquals(0, frames[0].frameIndex)
        assertEquals(1, frames[1].frameIndex)
        assertEquals(2, frames[2].frameIndex)
    }

    @Test fun deleteSession_cascadesFrameDeletion() = runTest {
        val sessionId = dao.insertSession(session())
        repeat(5) { dao.insertFrame(frame(sessionId, it)) }
        val toDelete = dao.getSessionById(sessionId)!!
        dao.deleteSession(toDelete)
        val frames = dao.getFramesForSession(sessionId)
        assertEquals("Frames should cascade-delete with session", 0, frames.size)
    }

    @Test fun framesFromDifferentSessions_doNotInterleave() = runTest {
        val id1 = dao.insertSession(session(name = "S1"))
        val id2 = dao.insertSession(session(name = "S2"))
        dao.insertFrame(frame(id1, 0))
        dao.insertFrame(frame(id2, 0))
        dao.insertFrame(frame(id1, 1))
        assertEquals(2, dao.getFramesForSession(id1).size)
        assertEquals(1, dao.getFramesForSession(id2).size)
    }
}
