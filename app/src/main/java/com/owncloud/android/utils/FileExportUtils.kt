/*
 *
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * Copyright (C) 2022 Tobias Kaminsky
 * Copyright (C) 2022 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.owncloud.android.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.owncloud.android.datamodel.OCFile
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class FileExportUtils {
    companion object {
        const val INITIAL_RENAME_COUNT = 2
    }

    @Throws(RuntimeException::class)
    fun exportFile(
        fileName: String,
        mimeType: String,
        contentResolver: ContentResolver,
        ocFile: OCFile?,
        file: File?
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            var uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)

            if (uri == null) {
                var count = INITIAL_RENAME_COUNT
                do {
                    val name = generateNewName(fileName, count)
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)

                    count++
                } while (uri == null)
            }

            try {
                val inputStream = if (ocFile != null) {
                    contentResolver.openInputStream(ocFile.storageUri)
                } else if (file != null) {
                    FileInputStream(file)
                } else {
                    throw RuntimeException("ocFile and file both may not be null")
                }

                val pfd = contentResolver.openFileDescriptor(uri, "w")
                val outputStream = FileOutputStream(pfd!!.fileDescriptor)
                IOUtils.copy(inputStream, outputStream)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            // < Android 10
            var outputStream: FileOutputStream? = null
            var inputStream: InputStream? = null
            try {
                var target = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )

                if (target.exists()) {
                    var count = INITIAL_RENAME_COUNT
                    do {
                        val name = generateNewName(fileName, count)
                        target = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            name
                        )

                        count++
                    } while (target.exists())
                }

                inputStream = if (ocFile != null) {
                    contentResolver.openInputStream(ocFile.storageUri)
                } else if (file != null) {
                    FileInputStream(file)
                } else {
                    throw RuntimeException("ocFile and file both may not be null")
                }

                outputStream = FileOutputStream(target)

                inputStream.use { fis ->
                    outputStream.use { os ->
                        val buffer = ByteArray(1024)
                        var len: Int
                        while (fis!!.read(buffer).also { len = it } != -1) {
                            os.write(buffer, 0, len)
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                outputStream?.close()
                inputStream?.close()
            }
        }
    }

    private fun generateNewName(name: String, count: Int): String {
        val extPos = name.lastIndexOf('.')
        val suffix = " ($count)"
        var extension = ""
        var nameWithoutExtension = ""

        if (extPos >= 0) {
            extension = name.substring(extPos + 1)
            nameWithoutExtension = name.substring(0, extPos)
        }

        return if (extPos >= 0) {
            "$nameWithoutExtension$suffix.$extension"
        } else {
            name + suffix
        }
    }
}
