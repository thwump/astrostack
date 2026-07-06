package com.astrostack.app.data

import androidx.room.*

// ─── Capture session (a set of RAW frames taken in one night) ─────────────────

@Entity(tableName = "capture_sessions")
data class CaptureSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Human-readable name, e.g. "M42 – 2026-07-05" */
    val name: String,
    /** Epoch millis of the first captured frame */
    val createdAt: Long = System.currentTimeMillis(),
    /** Total planned frames */
    val frameCount: Int,
    /** ISO used for all frames */
    val iso: Int,
    /** Exposure time in nanoseconds */
    val exposureTimeNs: Long,
    /** Absolute path to the directory containing DNG files */
    val directoryPath: String,
    /** Absolute path to the stacked result image, or null if not yet stacked */
    val stackedImagePath: String? = null,
    /** Stacking algorithm used (matches [StackingAlgorithm.name]) */
    val stackingAlgorithm: String? = null,
    /** Whether the session has been fully captured */
    val isCaptureDone: Boolean = false,
)

// ─── Individual RAW frame within a session ────────────────────────────────────

@Entity(
    tableName = "capture_frames",
    foreignKeys = [ForeignKey(
        entity = CaptureSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId"])],
)
data class CaptureFrame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 0-based frame index within the session */
    val frameIndex: Int,
    /** Absolute path to the .dng file */
    val filePath: String,
    val capturedAt: Long = System.currentTimeMillis(),
)

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface CaptureSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CaptureSession): Long

    @Update
    suspend fun updateSession(session: CaptureSession)

    @Delete
    suspend fun deleteSession(session: CaptureSession)

    @Query("SELECT * FROM capture_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): kotlinx.coroutines.flow.Flow<List<CaptureSession>>

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): CaptureSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFrame(frame: CaptureFrame): Long

    @Query("SELECT * FROM capture_frames WHERE sessionId = :sessionId ORDER BY frameIndex ASC")
    suspend fun getFramesForSession(sessionId: Long): List<CaptureFrame>

    @Query("UPDATE capture_sessions SET stackedImagePath = :path, stackingAlgorithm = :algorithm WHERE id = :sessionId")
    suspend fun updateStackedResult(sessionId: Long, path: String, algorithm: String)

    @Query("UPDATE capture_sessions SET isCaptureDone = 1 WHERE id = :sessionId")
    suspend fun markCaptureComplete(sessionId: Long)
}
