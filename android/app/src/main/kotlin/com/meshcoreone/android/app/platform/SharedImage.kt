// AndroidOnly: WP-407 User-invoked cached-image share, restricted content URI and transient read grant.
package com.meshcoreone.android.app.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun shareImage(context: Context, data: ByteArray) {
    context.startActivity(Intent.createChooser(sharedImageIntent(context, data), null))
}

internal suspend fun sharedImageIntent(context: Context, data: ByteArray): Intent {
    val (uri, mime) = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared-images")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create image sharing cache" }
        val cutoff = System.currentTimeMillis() - 86_400_000L
        val files = directory.listFiles() ?: throw IOException("Cannot list image sharing cache")
        files.filter { it.isFile && it.lastModified() < cutoff }.forEach {
            if (!it.delete()) throw IOException("Cannot remove expired shared image")
        }
        val file = File(directory, "${UUID.randomUUID()}.image")
        try { file.outputStream().use { it.write(data) } }
        catch (failure: IOException) {
            if (file.exists() && !file.delete()) failure.addSuppressed(IOException("Cannot remove incomplete shared image"))
            throw failure
        }
        FileProvider.getUriForFile(context, "${context.packageName}.shared-images", file) to
            (java.net.URLConnection.guessContentTypeFromStream(data.inputStream()) ?: "image/*")
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Image", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
