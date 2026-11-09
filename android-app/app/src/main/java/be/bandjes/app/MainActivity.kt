package be.bandjes.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.text.ParseException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
        val tokenStore = ApiTokenStore(preferences)
        val savedApiToken = tokenStore.read()
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
            MaterialTheme(
                colorScheme = if (darkMode) darkColorScheme(primary = Color(0xFF81CBB2))
                else lightColorScheme(primary = Accent),
            ) {
                Dashboard(
                    savedEndpoint = preferences.getString("endpoint", "").orEmpty(),
                    saveEndpoint = { preferences.edit().putString("endpoint", it).apply() },
                    savedDefaultSize = preferences.getString("default_size", "").orEmpty(),
                    saveDefaultSize = {
                        preferences.edit().putString("default_size", it).apply()
                    },
                    savedApiToken = savedApiToken,
                    saveApiToken = tokenStore::write,
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

private data class TyrePrice(
    val productId: String,
    val name: String,
    val size: String,
    val price: Double,
    val observedAt: String,
    val url: String,
    val favorite: Boolean,
)

private data class Summary(
    val tyreCount: Int,
    val lowestPrice: Double?,
    val medianPrice: Double?,
    val latestCollection: String?,
)

private data class DashboardData(
    val sizes: List<String>,
    val latestPrices: List<TyrePrice>,
    val history: List<TyrePrice>,
    val summary: Summary,
)

private class ApiTokenStore(
    private val preferences: android.content.SharedPreferences,
) {
    private val keyAlias = "bandjes_api_token_key"
    private val tokenPreference = "encrypted_api_token"

    fun read(): String {
        val stored = preferences.getString(tokenPreference, null) ?: return ""
        val parts = stored.split('.', limit = 2)
        require(parts.size == 2) { "The saved API token is invalid; clear and re-enter it in settings." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])),
        )
        return String(Base64.getDecoder().decode(parts[1]).let(cipher::doFinal), Charsets.UTF_8)
    }

    fun write(token: String) {
        if (token.isBlank()) {
            preferences.edit().remove(tokenPreference).apply()
            return
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = Base64.getEncoder().withoutPadding().encodeToString(cipher.iv)
        val ciphertext = Base64.getEncoder().withoutPadding()
            .encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
        preferences.edit().putString(tokenPreference, "$iv.$ciphertext").apply()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        return keyStore.getKey(keyAlias, null) as SecretKey
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
                favorite = row.optBoolean("favorite"),
            )
        }
    }

    val summaryJson = root.getJSONObject("summary")
    val lowest = summaryJson.optJSONObject("lowest_latest_price")
    return DashboardData(
        sizes = sizes,
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

private suspend fun fetchDashboard(
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

private suspend fun setFavorite(
    endpoint: String,
    token: String,
    size: String,
    productId: String,
    favorite: Boolean,
) = withContext(Dispatchers.IO) {
    require(token.isNotBlank()) { "Set the favorite API token in settings to update favorites." }
    val base = URL(endpoint)
    require(
        base.protocol == "https" &&
            !base.host.isNullOrBlank() &&
            (base.path == "service.php" || base.path.endsWith("/service.php")) &&
            base.ref == null
    ) {
        "Enter a valid HTTPS URL ending in service.php."
    }

    val connection = URL(base, "service-fav.php").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val requestBody = JSONObject()
            .put("size", size)
            .put("product_id", productId)
            .put("favorite", favorite)
            .toString()
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

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
        val response = JSONObject(body)
        check(
            response.optString("product_id") == productId &&
                response.optString("size") == size &&
                response.optBoolean("favorite") == favorite
        ) {
            "The service returned an unexpected favorite update result."
        }
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
    savedApiToken: String,
    saveApiToken: (String) -> Unit,
    darkMode: Boolean,
    saveDarkMode: (Boolean) -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf(savedEndpoint) }
    var apiToken by rememberSaveable { mutableStateOf(savedApiToken) }
    var defaultSize by rememberSaveable { mutableStateOf(savedDefaultSize) }
    var showSettings by remember { mutableStateOf(savedEndpoint.isBlank()) }
    var selectedSize by rememberSaveable { mutableStateOf(savedDefaultSize) }
    var selectedPeriod by rememberSaveable { mutableStateOf("all") }
    var latestPriceFilter by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatingProducts by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun refresh(size: String? = selectedSize.takeIf { it.isNotBlank() }) {
        loading = true
        error = null
        saveApiToken(apiToken.trim())
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
                    selectedSize = result.sizes.first()
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            selectedSize = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Service URL") },
                        placeholder = { Text("https://example.com/service.php") },
                        singleLine = true,
                    )
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = { apiToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Favorite API token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { refresh() },
                        enabled = !loading && endpoint.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loading) "Loading…" else "Load prices")
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
                    FavoriteChart(current.history)
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
                val visiblePrices = current.latestPrices.filter { tyre ->
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
                        updating = tyre.productId in updatingProducts,
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
                            if (apiToken.isBlank()) {
                                error = "Set the favorite API token in settings to update favorites."
                            } else {
                                updatingProducts = updatingProducts + tyre.productId
                                error = null
                                scope.launch {
                                    try {
                                        setFavorite(
                                            endpoint = endpoint.trim(),
                                            token = apiToken.trim(),
                                            size = selectedSize,
                                            productId = tyre.productId,
                                            favorite = !tyre.favorite,
                                        )
                                        data = fetchDashboard(
                                            endpoint.trim(),
                                            selectedSize,
                                            selectedPeriod,
                                        )
                                    } catch (exception: Exception) {
                                        error = exception.message
                                            ?: "Could not update this favorite."
                                    } finally {
                                        updatingProducts = updatingProducts - tyre.productId
                                    }
                                }
                            }
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
    updating: Boolean,
    onOpenProduct: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !updating,
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
