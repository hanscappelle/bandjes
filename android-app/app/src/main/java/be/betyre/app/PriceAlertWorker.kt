package be.betyre.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class PriceAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences("betyre", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("price_alert_enabled", false)) return Result.success()

        val threshold = preferences.getFloat("price_alert_threshold", 5f).toDouble()
        if (!threshold.isFinite() || threshold <= 0.0 || threshold > 100.0) {
            recordCheck(preferences, false, "The configured price-drop threshold is invalid.")
            return Result.failure()
        }

        val limitToFavorites = preferences.getBoolean(KEY_ALERT_FAVORITES_ONLY, true)
        val favoritesBySize = FavoriteStore(preferences).allFavoritesBySize()
            .filterValues { it.isNotEmpty() }
        if (limitToFavorites && favoritesBySize.isEmpty()) {
            recordCheck(preferences, true, "No favorite tyres are selected.")
            return Result.success()
        }

        val endpoint = preferences.getString("endpoint", "").orEmpty()
            .ifBlank { DEFAULT_SERVICE_URL }
        val currentPrices = mutableMapOf<String, TyrePrice>()
        try {
            if (limitToFavorites) {
                favoritesBySize.forEach { (size, favoriteIds) ->
                    fetchDashboard(endpoint, size, "7").latestPrices
                        .filter { it.productId in favoriteIds }
                        .forEach { currentPrices[it.productId] = it }
                }
            } else {
                val allSizes = fetchDashboard(endpoint, null, "7")
                allSizes.sizes.forEach { size ->
                    val dashboard = if (size == allSizes.selectedSize) {
                        allSizes
                    } else {
                        fetchDashboard(endpoint, size, "7")
                    }
                    dashboard.latestPrices.forEach { currentPrices[it.productId] = it }
                }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            recordCheck(
                preferences,
                false,
                exception.message ?: "Could not fetch prices from the service.",
            )
            return Result.retry()
        }

        val drops = mutableListOf<PriceDrop>()
        currentPrices.values.forEach { tyre ->
            val key = FavoriteStore.priceBaselineKey(tyre.productId)
            val previousPrice = preferences.getString(key, null)?.toDoubleOrNull()
            if (previousPrice != null && previousPrice > 0.0 && tyre.price < previousPrice) {
                val percentage = (previousPrice - tyre.price) / previousPrice * 100.0
                if (percentage >= threshold) {
                    drops += PriceDrop(tyre, percentage)
                }
            }
        }

        preferences.edit().apply {
            currentPrices.values.forEach { tyre ->
                putString(FavoriteStore.priceBaselineKey(tyre.productId), tyre.price.toString())
                putString(FavoriteStore.priceBaselineNameKey(tyre.productId), tyre.name)
                putString(FavoriteStore.priceBaselineSizeKey(tyre.productId), tyre.size)
                putString(FavoriteStore.priceBaselineNameKey(tyre.productId), tyre.name)
                putString(FavoriteStore.priceBaselineSizeKey(tyre.productId), tyre.size)
            }
        }.apply()

        if (drops.isNotEmpty()) showNotification(drops, limitToFavorites)
        recordCheck(
            preferences,
            true,
            "Checked ${currentPrices.size} ${if (limitToFavorites) "favorite " else ""}" +
                "tyre(s); ${drops.size} met the drop threshold.",
        )
        return Result.success()
    }

    private fun recordCheck(
        preferences: android.content.SharedPreferences,
        succeeded: Boolean,
        message: String,
    ) {
        preferences.edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_CHECK_SUCCEEDED, succeeded)
            .putString(KEY_LAST_CHECK_MESSAGE, message)
            .apply()
    }

    private fun showNotification(drops: List<PriceDrop>, limitToFavorites: Boolean) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tyre price alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = drops.map { drop ->
            val percentage = String.format(Locale.getDefault(), "%.1f%%", drop.percentage)
            "${drop.tyre.size} · ${drop.tyre.name}: " +
                "${formatAlertPrice(drop.tyre.price)} (-$percentage)"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                when {
                    limitToFavorites && drops.size == 1 -> "Favorite tyre price dropped"
                    limitToFavorites -> "${drops.size} favorite tyre prices dropped"
                    drops.size == 1 -> "Tyre price dropped"
                    else -> "${drops.size} tyre prices dropped"
                },
            )
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                lines.forEach(style::addLine)
            })
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private data class PriceDrop(val tyre: TyrePrice, val percentage: Double)

    private companion object {
        const val CHANNEL_ID = "favorite_price_alerts"
        const val NOTIFICATION_ID = 18055
    }
}

internal const val KEY_LAST_CHECK_AT = "price_alert_last_check_at"
internal const val KEY_LAST_CHECK_SUCCEEDED = "price_alert_last_check_succeeded"
internal const val KEY_LAST_CHECK_MESSAGE = "price_alert_last_check_message"
internal const val KEY_ALERT_FAVORITES_ONLY = "price_alert_favorites_only"

internal data class PriceAlertBaseline(
    val productId: String,
    val name: String,
    val size: String,
    val price: Double,
)

internal data class PriceAlertStatus(
    val checkedAt: Long?,
    val succeeded: Boolean?,
    val message: String?,
    val baselines: List<PriceAlertBaseline>,
)

internal fun loadPriceAlertStatus(
    preferences: android.content.SharedPreferences,
): PriceAlertStatus {
    val productSizes = if (preferences.getBoolean(KEY_ALERT_FAVORITES_ONLY, true)) {
        FavoriteStore(preferences).allFavoritesBySize()
            .flatMap { (size, productIds) -> productIds.map { it to size } }
            .toMap()
    } else {
        preferences.all.keys
            .filter { it.startsWith("alert_last_price:") }
            .associate { key -> key.removePrefix("alert_last_price:") to "" }
    }
    val baselines = productSizes.mapNotNull { (productId, favoriteSize) ->
        val price = preferences.getString(
            FavoriteStore.priceBaselineKey(productId),
            null,
        )?.toDoubleOrNull() ?: return@mapNotNull null
        PriceAlertBaseline(
            productId = productId,
            name = preferences.getString(
                FavoriteStore.priceBaselineNameKey(productId),
                "",
            ).orEmpty().ifBlank { "Product $productId" },
            size = preferences.getString(
                FavoriteStore.priceBaselineSizeKey(productId),
                favoriteSize,
            ).orEmpty(),
            price = price,
        )
    }.sortedWith(compareBy(PriceAlertBaseline::size, PriceAlertBaseline::name))

    return PriceAlertStatus(
        checkedAt = preferences.getLong(KEY_LAST_CHECK_AT, 0L).takeIf { it > 0L },
        succeeded = if (preferences.contains(KEY_LAST_CHECK_SUCCEEDED)) {
            preferences.getBoolean(KEY_LAST_CHECK_SUCCEEDED, false)
        } else {
            null
        },
        message = preferences.getString(KEY_LAST_CHECK_MESSAGE, null),
        baselines = baselines,
    )
}

internal fun postTestPriceAlertNotification(context: Context): Boolean {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                "favorite_price_alerts",
                "Tyre price alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, "favorite_price_alerts")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Test price alert")
        .setContentText("Notifications are working for betyre price alerts.")
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(18056, notification)
    return true
}

internal object PriceAlertScheduler {
    private const val WORK_NAME = "favorite-price-alert-check"

    fun configure(context: Context, enabled: Boolean, checksPerDay: Int) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val safeChecksPerDay = normalizePriceAlertChecksPerDay(checksPerDay)
        val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(
            (24L * 60L) / safeChecksPerDay,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request,
        )
    }
}

internal fun normalizePriceAlertChecksPerDay(checksPerDay: Int): Int {
    return checksPerDay.coerceIn(1, 4)
}

private fun formatAlertPrice(price: Double): String =
    NumberFormat.getCurrencyInstance(Locale.GERMANY).format(price)
