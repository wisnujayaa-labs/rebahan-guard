package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** Small app-private text files, written atomically (a crash mid-write keeps the old version). */
object TextFile {
    private const val TAG = "TextFile"

    fun read(context: Context, name: String): String? = try {
        AtomicFile(File(context.filesDir, name)).readFully().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $name", e)
        null
    }

    fun write(context: Context, name: String, text: String): Boolean {
        val file = AtomicFile(File(context.filesDir, name))
        var out: FileOutputStream? = null
        return try {
            out = file.startWrite()
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $name", e)
            if (out != null) file.failWrite(out)
            false
        }
    }
}
