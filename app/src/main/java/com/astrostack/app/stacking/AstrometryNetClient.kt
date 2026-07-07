package com.astrostack.app.stacking

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AstrometryNetClient {
    private const val BASE_URL = "https://nova.astrometry.net/api"

    private suspend fun postJson(endpoint: String, requestJson: JSONObject): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        
        val postData = "request-json=" + URLEncoder.encode(requestJson.toString(), "UTF-8")
        conn.outputStream.use { os ->
            os.write(postData.toByteArray(Charsets.UTF_8))
        }
        
        if (conn.responseCode != 200) {
            val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("HTTP error code: ${conn.responseCode} ${conn.responseMessage}. Details: $errStr")
        }
        
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    private suspend fun getJson(endpoint: String): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (conn.responseCode != 200) {
            val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("HTTP error code: ${conn.responseCode} ${conn.responseMessage}. Details: $errStr")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    private suspend fun uploadMultipart(session: String, jpegBytes: ByteArray): Int = withContext(Dispatchers.IO) {
        val boundary = "===Boundary---${System.currentTimeMillis()}==="
        val url = URL("$BASE_URL/upload")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        conn.outputStream.use { os ->
            val dos = DataOutputStream(os)
            
            // request-json field
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"request-json\"\r\n\r\n")
            val requestJson = JSONObject().apply { put("session", session) }
            dos.writeBytes(requestJson.toString() + "\r\n")

            // file field
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"\r\n")
            dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            dos.write(jpegBytes)
            dos.writeBytes("\r\n")

            dos.writeBytes("--$boundary--\r\n")
            dos.flush()
        }

        if (conn.responseCode != 200) {
            val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Upload failed: ${conn.responseCode} ${conn.responseMessage}. Details: $errStr")
        }

        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseStr)
        if (json.getString("status") != "success") {
            throw Exception("Upload response status: ${json.getString("status")}")
        }
        json.getInt("subid")
    }

    suspend fun solveImage(
        apiKey: String,
        bitmap: Bitmap,
        onProgress: (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        onProgress("Logging in to Astrometry.net…")
        val loginRes = postJson("/login", JSONObject().apply { put("apikey", apiKey) })
        val loginJson = JSONObject(loginRes)
        if (loginJson.getString("status") != "success") {
            throw Exception("Astrometry.net login failed: invalid API key")
        }
        val session = loginJson.getString("session")

        onProgress("Preparing image for solver…")
        // Downsample bitmap for upload to save bandwidth & memory
        val maxDim = 1200
        val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else 1f
        val uploadBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val baos = ByteArrayOutputStream()
        uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val jpegBytes = baos.toByteArray()
        if (scale < 1f) uploadBitmap.recycle()

        onProgress("Uploading image (${jpegBytes.size / 1024} KB)…")
        val subId = uploadMultipart(session, jpegBytes)

        onProgress("Waiting for solve (Submission: $subId)…")
        var jobId: Int? = null
        var attempts = 0
        while (attempts < 40) {
            delay(4000)
            try {
                val subStatusStr = getJson("/submissions/$subId")
                val subStatus = JSONObject(subStatusStr)
                val jobsArr = subStatus.optJSONArray("jobs")
                if (jobsArr != null && jobsArr.length() > 0) {
                    if (!jobsArr.isNull(0)) {
                        jobId = jobsArr.getInt(0)
                        break
                    }
                }
            } catch (e: Exception) {
                // Ignore temporary network errors during polling
            }
            attempts++
        }

        if (jobId == null) {
            throw Exception("Submission timed out waiting for job allocation.")
        }

        onProgress("Solving image on servers (Job: $jobId)…")
        attempts = 0
        var solved = false
        while (attempts < 40) {
            delay(4000)
            try {
                val jobStatusStr = getJson("/jobs/$jobId/info/")
                val jobStatus = JSONObject(jobStatusStr)
                val status = jobStatus.optString("status")
                if (status == "success") {
                    solved = true
                    break
                } else if (status == "failure") {
                    throw Exception("Astrometry.net could not solve this image.")
                }
            } catch (e: Exception) {
                // Ignore temporary network errors during polling
            }
            attempts++
        }

        if (!solved) {
            throw Exception("Solving timed out.")
        }

        onProgress("Retrieving identified objects…")
        val annotationsStr = getJson("/jobs/$jobId/annotations/")
        val annotationsJson = JSONObject(annotationsStr)
        val annotationsArr = annotationsJson.optJSONArray("annotations") ?: JSONArray()
        
        val objects = mutableListOf<String>()
        for (i in 0 until annotationsArr.length()) {
            val obj = annotationsArr.getJSONObject(i)
            val namesArr = obj.optJSONArray("names")
            if (namesArr != null) {
                for (j in 0 until namesArr.length()) {
                    val name = namesArr.getString(j)
                    if (!objects.contains(name) && !name.startsWith("HD ")) { // Filter out raw HD stars to keep list clean
                        objects.add(name)
                    }
                }
            }
        }
        
        objects
    }
}
