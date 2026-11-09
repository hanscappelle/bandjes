package be.bandjes.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.text.ParseException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.graphics.toArgb

internal const val DEFAULT_SERVICE_URL = "https://betyre.alwaysdata.net/service.php"
private const val REPOSITORY_URL = "https://github.com/hanscappelle/bandjes"
private val Accent = Color(0xFF176B55)
private val ChartColors = listOf(
    Color(0xFF176B55),
    Color(0xFFD26A32),
    Color(0xFF3569A8),
    Color(0xFF8D5CA6),
    Color(0xFFB44761),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("bandjes", MODE_PRIVATE)
        preferences.edit().remove("encrypted_api_token").apply()
        val favoriteStore = FavoriteStore(preferences)
        val priceAlertsEnabled = preferences.getBoolean("price_alert_enabled", false)
        val priceAlertChecksPerDay = normalizePriceAlertChecksPerDay(
            preferences.getInt("price_alert_checks_per_day", 2),
        )
        preferences.edit().putInt("price_alert_checks_per_day", priceAlertChecksPerDay).apply()
        if (priceAlertsEnabled) {
            PriceAlertScheduler.configure(this, true, priceAlertChecksPerDay)
        }
        setContent {
            val systemDarkMode = isSystemInDarkTheme()
            var darkMode by rememberSaveable {
                mutableStateOf(
                    if (preferences.contains("dark_mode")) {
                        preferences.getBoolean("dark_mode", false)
                    } else {
                        systemDarkMode
                    },
                )
            }
            SideEffect {
                window.navigationBarColor = if (darkMode) {
                    Color(0xFF121212).toArgb()
                } else {
                    Color.White.toArgb()
                }
                WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightNavigationBars = !darkMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
            }
            MaterialTheme(
                colorScheme = if (darkMode) darkColorScheme(primary = Color(0xFF81CBB2))
                else lightColorScheme(primary = Accent),
            ) {
                Dashboard(
                    savedEndpoint = preferences.getString("endpoint", "")
                        .orEmpty()
                        .ifBlank { DEFAULT_SERVICE_URL },
                    saveEndpoint = {
                        if (it.isBlank() || it == DEFAULT_SERVICE_URL) {
                            preferences.edit().remove("endpoint").apply()
                        } else {
                            preferences.edit().putString("endpoint", it).apply()
                        }
                    },
                    savedDefaultSize = preferences.getString("default_size", "").orEmpty(),
                    saveDefaultSize = {
                        preferences.edit().putString("default_size", it).apply()
                    },
                    favoriteStore = favoriteStore,
                    savedAlertEnabled = preferences.getBoolean("price_alert_enabled", false),
                    savedAlertThreshold = preferences.getFloat("price_alert_threshold", 5f),
                    savedAlertChecksPerDay = priceAlertChecksPerDay,
                    savePriceAlertSettings = { enabled, threshold, checksPerDay ->
                        preferences.edit()
                            .putBoolean("price_alert_enabled", enabled)
                            .putFloat("price_alert_threshold", threshold)
                            .putInt("price_alert_checks_per_day", checksPerDay)
                            .apply()
                        PriceAlertScheduler.configure(this, enabled, checksPerDay)
                    },
                    darkMode = darkMode,
                    saveDarkMode = {
                        darkMode = it
                        preferences.edit().putBoolean("dark_mode", it).apply()
                    },
                )
            }
        }
    }
}

internal data class TyrePrice(
    val productId: String,
    val name: String,
    val size: String,
    val price: Double,
    val observedAt: String,
    val url: String,
    val favorite: Boolean,
)

internal data class Summary(
    val tyreCount: Int,
    val lowestPrice: Double?,
    val medianPrice: Double?,
    val latestCollection: String?,
)

internal data class DashboardData(
    val sizes: List<String>,
    val selectedSize: String?,
    val latestPrices: List<TyrePrice>,
    val history: List<TyrePrice>,
    val summary: Summary,
)

internal class FavoriteStore(
    private val preferences: android.content.SharedPreferences,
) {
    fun read(size: String): Set<String> =
        preferences.getStringSet(preferenceKey(size), null)?.toSet().orEmpty()

    fun loadOrInitialize(size: String): Set<String> {
        val key = preferenceKey(size)
        val stored = preferences.getStringSet(key, null)
        if (stored != null) return stored.toSet()

        preferences.edit().putStringSet(key, emptySet()).apply()
        return emptySet()
    }

    fun write(size: String, productIds: Set<String>) {
        preferences.edit().putStringSet(preferenceKey(size), productIds.toSet()).apply()
    }

    fun allFavoritesBySize(): Map<String, Set<String>> =
        preferences.all.keys
            .filter { it.startsWith(FAVORITE_PRODUCT_IDS_PREFIX) }
            .associate { key ->
                key.removePrefix(FAVORITE_PRODUCT_IDS_PREFIX) to
                    (preferences.getStringSet(key, null)?.toSet().orEmpty())
            }

    fun setPriceBaseline(productId: String, price: Double?) {
        val editor = preferences.edit()
        if (price == null) {
            editor.remove(priceBaselineKey(productId))
        } else {
            editor.putString(priceBaselineKey(productId), price.toString())
        }
        editor.apply()
    }

    private fun preferenceKey(size: String) = "favorite_product_ids:$size"

    companion object {
        const val FAVORITE_PRODUCT_IDS_PREFIX = "favorite_product_ids:"
        fun priceBaselineKey(productId: String) = "alert_last_price:$productId"
    }
}

private fun parseDashboard(json: String): DashboardData {
    val root = JSONObject(json)
    val sizeArray = root.getJSONArray("available_sizes")
    val sizes = (0 until sizeArray.length()).map { index ->
        sizeArray.getJSONObject(index).getString("size")
    }

    fun parsePrices(key: String): List<TyrePrice> {
        val rows = root.getJSONArray(key)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            TyrePrice(
                productId = row.getString("product_id"),
                name = row.getString("name"),
                size = row.getString("size"),
                price = row.getDouble("price_eur"),
                observedAt = row.getString("observed_at"),
                url = row.optString("url"),
                favorite = false,
            )
        }
    }

    val summaryJson = root.getJSONObject("summary")
    val lowest = summaryJson.optJSONObject("lowest_latest_price")
    return DashboardData(
        sizes = sizes,
        selectedSize = root.getJSONObject("filters").optString("size")
            .takeUnless { it == "null" || it.isBlank() },
        latestPrices = parsePrices("latest_prices"),
        history = parsePrices("history"),
        summary = Summary(
            tyreCount = summaryJson.getInt("tyre_count"),
            lowestPrice = lowest?.optDouble("price_eur")?.takeUnless { it.isNaN() },
            medianPrice = summaryJson.optDouble("median_latest_price_eur")
                .takeUnless { it.isNaN() },
            latestCollection = summaryJson.optString("last_collection_at")
                .takeUnless { it == "null" || it.isBlank() },
        ),
    )
}

internal suspend fun fetchDashboard(
    endpoint: String,
    size: String?,
    period: String,
): DashboardData = withContext(Dispatchers.IO) {
    val base = URL(endpoint)
    require(
        base.protocol == "https" &&
            !base.host.isNullOrBlank() &&
            (base.path == "service.php" || base.path.endsWith("/service.php")) &&
            base.ref == null
    ) {
        "Enter a valid HTTPS URL ending in service.php."
    }
    val query = buildList {
        if (size != null) add("size=${URLEncoder.encode(size, "UTF-8")}")
        add("period=${URLEncoder.encode(period, "UTF-8")}")
    }.joinToString("&")
    val separator = if (base.query == null) "?" else "&"
    val connection = URL("$endpoint$separator$query").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (status !in 200..299) {
            val message = try {
                JSONObject(body).optString("error")
            } catch (_: JSONException) {
                ""
            }
            throw IllegalStateException(message.ifBlank { "Service returned HTTP $status." })
        }
        parseDashboard(body)
    } finally {
        connection.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(
    savedEndpoint: String,
    saveEndpoint: (String) -> Unit,
    savedDefaultSize: String,
    saveDefaultSize: (String) -> Unit,
    favoriteStore: FavoriteStore,
    savedAlertEnabled: Boolean,
    savedAlertThreshold: Float,
    savedAlertChecksPerDay: Int,
    savePriceAlertSettings: (Boolean, Float, Int) -> Unit,
    darkMode: Boolean,
    saveDarkMode: (Boolean) -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf(savedEndpoint) }
    var defaultSize by rememberSaveable { mutableStateOf(savedDefaultSize) }
    var showSettings by remember { mutableStateOf(savedEndpoint.isBlank()) }
    var selectedSize by rememberSaveable { mutableStateOf(savedDefaultSize) }
    var selectedPeriod by rememberSaveable { mutableStateOf("all") }
    var latestPriceFilter by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var favoriteIds by remember(selectedSize) {
        mutableStateOf(favoriteStore.read(selectedSize))
    }
    var alertEnabled by rememberSaveable { mutableStateOf(savedAlertEnabled) }
    var alertThreshold by rememberSaveable {
        mutableStateOf(savedAlertThreshold.toString())
    }
    var alertChecksPerDay by rememberSaveable {
        mutableStateOf(normalizePriceAlertChecksPerDay(savedAlertChecksPerDay).toString())
    }
    var alertSettingsMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            error = "Allow notifications in Android settings to receive price alerts."
        }
    }

    fun persistAlertSettings(enabled: Boolean): Boolean {
        val threshold = alertThreshold.toFloatOrNull()
        if (threshold == null || threshold <= 0f || threshold > 100f) {
            alertSettingsMessage = "Enter a drop threshold greater than 0 and at most 100%."
            return false
        }
        val checks = alertChecksPerDay.toIntOrNull()
        if (checks == null || checks !in 1..2) {
            alertSettingsMessage = "Choose once or twice per day."
            return false
        }
        savePriceAlertSettings(enabled, threshold, checks)
        alertSettingsMessage = null
        return true
    }

    fun refresh(size: String? = selectedSize.takeIf { it.isNotBlank() }) {
        loading = true
        error = null
        scope.launch {
            try {
                var result = fetchDashboard(endpoint.trim(), size, selectedPeriod)
                if (size == null && defaultSize.isNotBlank() && defaultSize in result.sizes) {
                    selectedSize = defaultSize
                    result = fetchDashboard(endpoint.trim(), defaultSize, selectedPeriod)
                } else if (defaultSize.isNotBlank() && defaultSize !in result.sizes) {
                    defaultSize = ""
                    saveDefaultSize("")
                }
                data = result
                if (selectedSize !in result.sizes && result.sizes.isNotEmpty()) {
                    selectedSize = result.selectedSize
                        ?.takeIf { it in result.sizes }
                        ?: result.sizes.first()
                }
                val loadedSize = result.selectedSize
                    ?.takeIf { it in result.sizes }
                    ?: selectedSize
                if (loadedSize.isNotBlank()) {
                    selectedSize = loadedSize
                    favoriteIds = favoriteStore.loadOrInitialize(loadedSize)
                }
                saveEndpoint(endpoint.trim())
                showSettings = false
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load dashboard data."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (endpoint.isNotBlank()) {
            refresh(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🏍️ Tyre Price Watch") },
                actions = {
                    if (endpoint.isNotBlank()) {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Text("⚙", fontSize = 22.sp)
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                if (!loading && endpoint.isNotBlank()) {
                    refresh()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showSettings || endpoint.isBlank()) {
                    item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Dark mode", modifier = Modifier.weight(1f))
                        Switch(
                            checked = darkMode,
                            onCheckedChange = saveDarkMode,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = {
                                endpoint = it
                                selectedSize = ""
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Service URL") },
                            placeholder = { Text("https://example.com/service.php") },
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                endpoint = DEFAULT_SERVICE_URL
                                selectedSize = ""
                                saveEndpoint("")
                            },
                            enabled = endpoint != DEFAULT_SERVICE_URL,
                        ) {
                            Text("Use default")
                        }
                    }
                    val availableSizes = data?.sizes.orEmpty()
                    if (availableSizes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FilterDropdown(
                            label = "Default tyre size",
                            value = defaultSize,
                            options = listOf("") + availableSizes,
                            labels = mapOf("" to "First available"),
                            modifier = Modifier.fillMaxWidth(),
                        ) { newDefaultSize ->
                            defaultSize = newDefaultSize
                            saveDefaultSize(newDefaultSize)
                            if (newDefaultSize.isBlank()) {
                                selectedSize = availableSizes.first()
                                refresh(null)
                            } else {
                                selectedSize = newDefaultSize
                                refresh(newDefaultSize)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Favorite price alerts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Notify on price drops", modifier = Modifier.weight(1f))
                        Switch(
                            checked = alertEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    alertEnabled = false
                                    savePriceAlertSettings(
                                        false,
                                        savedAlertThreshold,
                                        normalizePriceAlertChecksPerDay(savedAlertChecksPerDay),
                                    )
                                    alertSettingsMessage = null
                                } else if (persistAlertSettings(true)) {
                                    alertEnabled = true
                                    if (
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    }
                                } else {
                                    alertEnabled = false
                                }
                            },
                        )
                    }
                    Text(
                        "Alert when a favorite's price drops by at least this percentage since the previous check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = alertThreshold,
                        onValueChange = {
                            alertThreshold = it
                            persistAlertSettings(alertEnabled)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Price drop threshold (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    FilterDropdown(
                        label = "Checks per day",
                        value = alertChecksPerDay,
                        options = listOf("1", "2"),
                        labels = mapOf("1" to "Once per day", "2" to "Twice per day"),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        alertChecksPerDay = it
                        persistAlertSettings(alertEnabled)
                    }
                    alertSettingsMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Alert settings save automatically when changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (alertEnabled) {
                        Text(
                            "Checks are scheduled by Android and may be delayed by battery or network restrictions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { refresh() },
                        enabled = !loading && endpoint.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loading) "Loading…" else "Load prices")
                    }
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)),
                                )
                            } catch (_: ActivityNotFoundException) {
                                error = "No app is available to open the GitHub repository."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Feature requests and bug reports")
                    }
                }
            }

            if (error != null) {
                item {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val current = data
            if (current != null) {
                val latestPrices = current.latestPrices.map { tyre ->
                    tyre.copy(favorite = tyre.productId in favoriteIds)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterDropdown(
                            label = "Size",
                            value = selectedSize,
                            options = current.sizes,
                            modifier = Modifier.weight(1f),
                            labelInButton = true,
                        ) { newSize ->
                            selectedSize = newSize
                            refresh(newSize)
                        }
                        FilterDropdown(
                            label = "History",
                            value = selectedPeriod,
                            options = listOf("all", "7", "30", "90"),
                            labels = mapOf(
                                "all" to "All",
                                "7" to "7 days",
                                "30" to "30 days",
                                "90" to "90 days",
                            ),
                            modifier = Modifier.weight(1f),
                            labelInButton = true,
                        ) { newPeriod ->
                            selectedPeriod = newPeriod
                            refresh()
                        }
                    }
                }
                item {
                    Text(
                        "Favorite price history",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    FavoriteChart(current.history.filter { it.productId in favoriteIds })
                }
                item {
                    SummaryCards(current.summary)
                }
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Latest prices",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            OutlinedTextField(
                                value = latestPriceFilter,
                                onValueChange = { latestPriceFilter = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Filter") },
                                singleLine = true,
                            )
                        }
                        Text(
                            "Tap a tyre to open its product page. Long-press to toggle favorite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Favorites only", modifier = Modifier.weight(1f))
                            Switch(
                                checked = favoritesOnly,
                                onCheckedChange = { favoritesOnly = it },
                            )
                        }
                    }
                }
                val visiblePrices = latestPrices.filter { tyre ->
                    val query = latestPriceFilter.trim()
                    (!favoritesOnly || tyre.favorite) &&
                        (query.isEmpty() || listOf(
                            tyre.name,
                            tyre.productId,
                            tyre.size,
                            tyre.price.toString(),
                            tyre.observedAt,
                        ).any { value -> value.contains(query, ignoreCase = true) })
                }
                if (visiblePrices.isEmpty()) {
                    item {
                        Text(
                            if (favoritesOnly) "No favorites match this filter."
                            else "No tyres match this filter.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(visiblePrices, key = { it.productId }) { tyre ->
                    PriceRow(
                        tyre = tyre,
                        onOpenProduct = {
                            val productUri = Uri.parse(tyre.url)
                            if (productUri.scheme !in setOf("http", "https")
                                || productUri.host.isNullOrBlank()
                            ) {
                                error = "This tyre has an invalid product page URL."
                            } else {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, productUri),
                                    )
                                } catch (_: ActivityNotFoundException) {
                                    error = "No app is available to open this product page."
                                }
                            }
                        },
                        onToggleFavorite = {
                            val updatedFavoriteIds = if (tyre.favorite) {
                                favoriteIds - tyre.productId
                            } else {
                                favoriteIds + tyre.productId
                            }
                            favoriteStore.write(selectedSize, updatedFavoriteIds)
                            favoriteStore.setPriceBaseline(
                                tyre.productId,
                                if (tyre.favorite) null else tyre.price,
                            )
                            favoriteIds = updatedFavoriteIds
                            error = null
                        },
                    )
                }
            } else if (error == null) {
                item {
                    Text(
                        "Enter the HTTPS URL for your service.php endpoint to get started.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    labels: Map<String, String> = emptyMap(),
    labelInButton: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (!labelInButton) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
        }
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (labelInButton) "$label: ${labels[value] ?: value.ifBlank { "Select" }}"
                else labels[value] ?: value.ifBlank { "Select" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels[option] ?: option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCards(summary: Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Tyres", summary.tyreCount.toString(), Modifier.weight(1f))
            MetricCard("Lowest", summary.lowestPrice?.let(::formatPrice) ?: "—", Modifier.weight(1f))
            MetricCard("Median", summary.medianPrice?.let(::formatPrice) ?: "—", Modifier.weight(1f))
        }
        summary.latestCollection?.let {
            Text(
                "Last collection: $it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PriceRow(
    tyre: TyrePrice,
    onOpenProduct: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenProduct,
                onLongClick = onToggleFavorite,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (tyre.favorite) "★ " else "") + tyre.name,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${tyre.size} · ${tyre.observedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatPrice(tyre.price),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FavoriteChart(history: List<TyrePrice>) {
    val grouped = history.groupBy { it.productId }.values
    if (grouped.isEmpty()) {
        Text(
            "Favorite tyres from the web dashboard to see their price history.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                val allPrices = history.map { it.price.toFloat() }
                val minPrice = allPrices.minOrNull() ?: return@Canvas
                val maxPrice = allPrices.maxOrNull() ?: return@Canvas
                val allTimes = history.map { parseDate(it.observedAt) }
                val minTime = allTimes.minOrNull() ?: return@Canvas
                val timeRange = (allTimes.maxOrNull() ?: minTime) - minTime
                val range = (maxPrice - minPrice).takeIf { it > 0f } ?: 1f
                val left = 8f
                val right = size.width - 8f
                val top = 8f
                val bottom = size.height - 8f

                repeat(4) { index ->
                    val y = top + (bottom - top) * index / 3f
                    drawLine(
                        color = gridColor,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                grouped.forEachIndexed { seriesIndex, points ->
                    val sorted = points.sortedBy { parseDate(it.observedAt) }
                    if (sorted.isNotEmpty()) {
                        val times = sorted.map { parseDate(it.observedAt) }
                        val path = Path()
                        sorted.forEachIndexed { index, point ->
                            val x = if (timeRange == 0L) {
                                (left + right) / 2f
                            } else {
                                left + (times[index] - minTime).toFloat() /
                                    timeRange.toFloat() * (right - left)
                            }
                            val y = bottom - (point.price.toFloat() - minPrice) / range * (bottom - top)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            drawCircle(
                                color = ChartColors[seriesIndex % ChartColors.size],
                                radius = 4.dp.toPx(),
                                center = Offset(x, y),
                            )
                        }
                        drawPath(
                            path = path,
                            color = ChartColors[seriesIndex % ChartColors.size],
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            grouped.forEachIndexed { index, points ->
                val first = points.first()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        drawCircle(ChartColors[index % ChartColors.size])
                    }
                    Text(
                        "${first.name} (${first.size})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatPrice(price: Double): String =
    NumberFormat.getCurrencyInstance(Locale.GERMANY).format(price)

private fun parseDate(value: String): Long {
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )
    for (pattern in patterns) {
        val format = java.text.SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
        }
        try {
            format.parse(value)?.let { return it.time }
        } catch (_: ParseException) {
            continue
        }
    }
    throw IllegalArgumentException("Invalid observation timestamp: $value")
}
