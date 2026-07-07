package com.astrostack.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for capture sessions and frames.
 * Combines the Room database with the filesystem.
 */
@Singleton
class ImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val dao = db.captureSessionDao()

    // ─── Sessions ─────────────────────────────────────────────────────────────

    fun getAllSessions(): Flow<List<CaptureSession>> = dao.getAllSessions()

    suspend fun getSessionById(id: Long): CaptureSession? = dao.getSessionById(id)

    /**
     * Creates a new capture session record.
     * @return The auto-generated session ID.
     */
    suspend fun createSession(
        name: String,
        frameCount: Int,
        iso: Int,
        exposureNs: Long,
        directoryPath: String,
    ): Long = dao.insertSession(
        CaptureSession(
            name = name,
            frameCount = frameCount,
            iso = iso,
            exposureTimeNs = exposureNs,
            directoryPath = directoryPath,
        )
    )

    suspend fun markCaptureComplete(sessionId: Long) = dao.markCaptureComplete(sessionId)

    suspend fun saveStackedResult(sessionId: Long, imagePath: String, algorithm: String) =
        dao.updateStackedResult(sessionId, imagePath, algorithm)

    suspend fun deleteSession(sessionId: Long) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.deleteSession(session)
        // Remove files from disk
        File(session.directoryPath).deleteRecursively()
        session.stackedImagePath?.let { File(it).delete() }
    }

    // ─── Frames ───────────────────────────────────────────────────────────────

    suspend fun addFrame(sessionId: Long, filePath: String, frameIndex: Int): Long =
        dao.insertFrame(CaptureFrame(sessionId = sessionId, filePath = filePath, frameIndex = frameIndex))

    suspend fun getFramesForSession(sessionId: Long): List<CaptureFrame> =
        dao.getFramesForSession(sessionId)

    /** Returns [File] objects for all captured DNG frames in [sessionId]. */
    suspend fun getDngFilesForSession(sessionId: Long): List<File> =
        dao.getFramesForSession(sessionId).map { File(it.filePath) }.filter { it.exists() }

    // ─── Disk helpers ─────────────────────────────────────────────────────────

    /** Directory where stacked results are written. */
    fun getStackedOutputDir(): File =
        File(context.filesDir, "stacked").also { it.mkdirs() }

    fun getTotalStorageUsedBytes(): Long {
        val capturesDir = File(context.filesDir, "captures")
        val stackedDir = File(context.filesDir, "stacked")
        return (capturesDir.walkTopDown() + stackedDir.walkTopDown())
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    // ─── MediaStore (public Pictures) ─────────────────────────────────────────

    /**
     * Saves [bitmap] as a PNG into the public **Pictures/AstroStack** folder so it
     * appears in Google Photos and is accessible via USB without root.
     *
     * On Android 10+ (API 29) uses [MediaStore.Images] scoped storage API.
     * On Android 9 and below writes directly to [Environment.DIRECTORY_PICTURES].
     *
     * @param bitmap      The stacked result bitmap to export.
     * @param displayName Filename without extension, e.g. "stacked_session3_20260705".
     * @return            The public URI string on success, or null on failure.
     */
    suspend fun saveToPublicPictures(bitmap: Bitmap, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage: insert a pending entry, write, then mark as complete
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/AstroStack")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                // Mark as not pending so it appears in the gallery
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                // Legacy path for API 26–28
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "AstroStack"
                ).also { it.mkdirs() }
                val file = File(dir, "$displayName.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Notify the media scanner
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/png"), null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.e("AstroStack", "Failed to save to public Pictures", e)
            null
        }
    }

    /**
     * Saves raw bytes of an exported file (TIFF, FITS) into the public **Pictures/AstroStack** folder.
     */
    suspend fun saveExportFileToPublicPictures(
        displayName: String,
        mimeType: String,
        extension: String,
        writeBlock: (OutputStream) -> Unit
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.$extension")
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/AstroStack")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    writeBlock(out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "AstroStack"
                ).also { it.mkdirs() }
                val file = File(dir, "$displayName.$extension")
                FileOutputStream(file).use { out ->
                    writeBlock(out)
                }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf(mimeType), null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.e("AstroStack", "Failed to save export file $extension to public Pictures", e)
            null
        }
    }
}

