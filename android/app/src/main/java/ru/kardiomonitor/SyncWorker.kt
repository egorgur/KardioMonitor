package ru.kardiomonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.platform.ble.GattClient
import ru.kardiomonitor.platform.ble.withResponseTimeout
import ru.kardiomonitor.data.BandConfig
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация раз в 30 минут. Android может откладывать запуск в режиме
 * энергосбережения (Doze), поэтому интервал не строгий.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val manager: BandManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = BandConfig.load(applicationContext) ?: return Result.success()
        if (!hasBluetoothPermission(applicationContext)) {
            Log.w(GattClient.TAG, "фоновая синхронизация: нет разрешения на Bluetooth")
            return Result.success()
        }
        return try {
            withResponseTimeout(SYNC_TIMEOUT_MS, "фоновая синхронизация") {
                manager.backgroundSync(config)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Не повторяем сразу: браслета может не быть рядом. Следующая попытка — через 30 минут.
            Log.w(GattClient.TAG, "фоновая синхронизация не удалась", e)
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "band-sync"
        private const val SYNC_TIMEOUT_MS = 3 * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun hasBluetoothPermission(context: Context): Boolean {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }
    }
}
