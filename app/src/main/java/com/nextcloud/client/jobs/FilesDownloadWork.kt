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

package com.nextcloud.client.jobs

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.User
import com.owncloud.android.R
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.files.services.FileDownloader
import com.owncloud.android.operations.DownloadType
import com.owncloud.android.ui.dialog.SendShareDialog
import com.owncloud.android.ui.fragment.OCFileListFragment
import com.owncloud.android.ui.notifications.NotificationUtils
import com.owncloud.android.utils.FileExportUtils
import com.owncloud.android.utils.FileStorageUtils
import com.owncloud.android.utils.theme.ThemeColorUtils
import java.security.SecureRandom

class FilesDownloadWork(
    private val appContext: Context,
    private val user: User,
    private val contentResolver: ContentResolver,
    private val themeColorUtils: ThemeColorUtils,
    params: WorkerParameters
) : Worker(appContext, params) {
    companion object {
        const val FILES_TO_DOWNLOAD = "files_to_download"
        private const val NUMERIC_NOTIFICATION_ID = "NUMERIC_NOTIFICATION_ID"
    }

    override fun doWork(): Result {
        val fileIDs = inputData.getLongArray(FILES_TO_DOWNLOAD) ?: LongArray(0)

        val storageManager = FileDataStorageManager(user, contentResolver)

        var successfulExports = 0
        for (fileID in fileIDs) {
            val ocFile = storageManager.getFileById(fileID) ?: continue

            // check if storage is left
            if (!FileStorageUtils.checkIfEnoughSpace(ocFile)) {
                showErrorNotification(successfulExports, fileIDs.size)
                break
            }

            if (ocFile.isDown) {
                try {
                    exportFile(ocFile)
                } catch (e: java.lang.RuntimeException) {
                    showErrorNotification(successfulExports, fileIDs.size)
                }
            } else {
                // TODO catch/handle error
                downloadFile(ocFile)
            }

            successfulExports++
        }

        // show notification
        showSuccessNotification(successfulExports)

        return Result.success()
    }

    @Throws(RuntimeException::class)
    private fun exportFile(ocFile: OCFile) {
        FileExportUtils().exportFile(ocFile.fileName, ocFile.mimeType, contentResolver, ocFile, null)
    }

    private fun downloadFile(ocFile: OCFile) {
        val i = Intent(appContext, FileDownloader::class.java)
        i.putExtra(FileDownloader.EXTRA_USER, user)
        i.putExtra(FileDownloader.EXTRA_FILE, ocFile)
        i.putExtra(SendShareDialog.PACKAGE_NAME, "")
        i.putExtra(SendShareDialog.ACTIVITY_NAME, "")
        i.putExtra(OCFileListFragment.DOWNLOAD_TYPE, DownloadType.EXPORT)
        appContext.startService(i)
    }

    private fun showErrorNotification(successfulExports: Int, size: Int) {
        if (successfulExports == 0) {
            showNotification(
                appContext.getString(
                    R.string.download_failed,
                    appContext.resources.getQuantityString(R.plurals.files, size)
                )
            )
        } else {
            showNotification(
                appContext.getString(
                    R.string.download_partially_failed,
                    appContext.resources.getQuantityString(R.plurals.files, successfulExports),
                    appContext.resources.getQuantityString(R.plurals.files, size)
                )
            )
        }
    }

    private fun showSuccessNotification(successfulExports: Int) {
        val files = appContext.resources.getQuantityString(R.plurals.files, successfulExports, successfulExports)
        showNotification(
            appContext.getString(
                R.string.successful_download,
                files
            )
        )
    }

    private fun showNotification(message: String) {
        val notificationId = SecureRandom().nextInt()

        val notificationBuilder = NotificationCompat.Builder(
            appContext,
            NotificationUtils.NOTIFICATION_CHANNEL_DOWNLOAD
        )
            .setSmallIcon(R.drawable.notification_icon)
            .setLargeIcon(BitmapFactory.decodeResource(appContext.resources, R.drawable.notification_icon))
            .setColor(themeColorUtils.primaryColor(appContext))
            .setSubText(user.accountName)
            .setContentText(message)
            .setAutoCancel(true)

        val actionIntent = Intent(appContext, NotificationReceiver::class.java).apply {
            putExtra(NUMERIC_NOTIFICATION_ID, notificationId)
        }
        val actionPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId,
            actionIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationBuilder.addAction(
            NotificationCompat.Action(
                null,
                appContext.getString(R.string.locate_folder),
                actionPendingIntent
            )
        )

        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    class NotificationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // open file chooser
            val openIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                setDataAndType(Uri.parse(Environment.DIRECTORY_DOWNLOADS), "*/*")
                flags = FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(openIntent)

            // remove notification
            val numericNotificationId = intent.getIntExtra(NUMERIC_NOTIFICATION_ID, 0)

            if (numericNotificationId != 0) {
                val notificationManager = context.getSystemService(Activity.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(numericNotificationId)
            }
        }
    }
}
