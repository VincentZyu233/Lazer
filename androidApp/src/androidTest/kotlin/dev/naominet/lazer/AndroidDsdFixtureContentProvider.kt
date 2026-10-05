package dev.naominet.lazer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Test-only SAF-like provider exposing seekable files and non-seekable pipe descriptors. */
class AndroidDsdFixtureContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = fixtureName(uri)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply {
            val row = Array<Any?>(columns.size) { index ->
                when (columns[index]) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> -1L
                    else -> null
                }
            }
            addRow(row)
        }
    }

    override fun getType(uri: Uri): String = when (fixtureName(uri).substringAfterLast('.')) {
        "dsf" -> "audio/x-dsf"
        "dff" -> "audio/x-dff"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("DSD test fixtures are read-only.")
        val name = fixtureName(uri)
        return when (uri.pathSegments.firstOrNull()) {
            "seekable" -> openSeekableFixture(name)
            "pipe" -> openPipeFixture(name)
            else -> throw FileNotFoundException("Unknown DSD fixture access mode.")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("The DSD fixture provider is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("The DSD fixture provider is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("The DSD fixture provider is read-only.")

    private fun fixtureName(uri: Uri): String {
        if (uri.authority != AUTHORITY || uri.pathSegments.size != 2) {
            throw IllegalArgumentException("Invalid DSD fixture URI.")
        }
        val name = uri.pathSegments[1]
        if (name !in FIXTURE_NAMES) throw FileNotFoundException("Unknown DSD fixture.")
        return name
    }

    private fun openSeekableFixture(name: String): ParcelFileDescriptor {
        val appContext = context ?: throw FileNotFoundException("Provider is not attached.")
        val file = File(appContext.cacheDir, "dsd-content-fixture-$name")
        try {
            appContext.assets.open(name).use { input ->
                file.outputStream().use(input::copyTo)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (failure: IOException) {
            file.delete()
            throw FileNotFoundException(failure.message)
        }
    }

    private fun openPipeFixture(name: String): ParcelFileDescriptor {
        val appContext = context ?: throw FileNotFoundException("Provider is not attached.")
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                    appContext.assets.open(name).use { input -> input.copyTo(output) }
                }
            } catch (_: IOException) {
                runCatching { writeEnd.close() }
            }
        }, "lazer-dsd-test-provider").apply {
            isDaemon = true
            start()
        }
        return readEnd
    }

    private companion object {
        const val AUTHORITY = "dev.naominet.lazer.androidtest.dsd"
        val FIXTURE_NAMES = setOf(
            "dsd64_test.dsf",
            "dff64_test.dff",
            "dst64_verbatim.dff",
            "malformed_test.dsf",
        )
    }
}
