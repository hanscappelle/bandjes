<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const ROOT = __DIR__ . '/..';

function respond(array $payload, int $status = 200): void
{
    http_response_code($status);
    echo json_encode(
        $payload,
        JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR
    ) . "\n";
}

function normalize_size(string $value): ?string
{
    if (!preg_match(
        '/^(\d{2,3})\s*\/\s*(\d{2,3})\s*(?:-|\/|\s)?\s*(?:ZR|VB|R|B)?\s*(\d{2})$/i',
        trim($value),
        $matches
    )) {
        return null;
    }

    return $matches[1] . '/' . $matches[2] . 'R' . $matches[3];
}

function load_common_sizes(): array
{
    $path = ROOT . '/common_motorcycle_tyre_sizes.csv';
    if (!is_file($path) || !is_readable($path)) {
        throw new RuntimeException('Could not read common_motorcycle_tyre_sizes.csv.');
    }

    $file = fopen($path, 'rb');
    if ($file === false) {
        throw new RuntimeException('Could not open common_motorcycle_tyre_sizes.csv.');
    }

    try {
        $header = fgetcsv($file, null, ',', '"', '\\');
        if ($header === false) {
            throw new RuntimeException('The common tyre sizes CSV is empty.');
        }

        $indexes = array_flip($header);
        if (!isset($indexes['size'], $indexes['position'])) {
            throw new RuntimeException('The common tyre sizes CSV is missing required columns.');
        }

        $sizes = [];
        while (($row = fgetcsv($file, null, ',', '"', '\\')) !== false) {
            $size = normalize_size((string)($row[$indexes['size']] ?? ''));
            if ($size !== null) {
                $sizes[$size] = (string)($row[$indexes['position']] ?? '');
            }
        }

        return $sizes;
    } finally {
        fclose($file);
    }
}

function db_connection(): PDO
{
    $configuredPath = getenv('TYRE_PRICE_DB');
    $databasePath = $configuredPath !== false && trim($configuredPath) !== ''
        ? trim($configuredPath)
        : ROOT . '/tyre_prices.sqlite3';

    if (!is_file($databasePath) || !is_readable($databasePath)) {
        throw new RuntimeException('The SQLite database does not exist or is not readable.');
    }

    $connection = new PDO('sqlite:' . $databasePath, null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    $connection->exec('PRAGMA query_only = ON');
    $connection->exec('PRAGMA busy_timeout = 5000');

    return $connection;
}

function observation_timestamp_column(PDO $connection): string
{
    $columns = $connection->query('PRAGMA table_info(price_observations)')->fetchAll();
    $names = array_column($columns, 'name');
    if (in_array('observed_at', $names, true)) {
        return 'observed_at';
    }
    if (in_array('observed_on', $names, true)) {
        return 'observed_on';
    }

    throw new RuntimeException('price_observations has neither observed_at nor observed_on.');
}

function median(array $values): ?float
{
    if (!$values) {
        return null;
    }

    sort($values, SORT_NUMERIC);
    $middle = intdiv(count($values), 2);

    return count($values) % 2 === 0
        ? ((float)$values[$middle - 1] + (float)$values[$middle]) / 2
        : (float)$values[$middle];
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') {
    header('Allow: GET');
    respond(['error' => 'Only GET requests are supported.'], 405);
    exit;
}
if (isset($_GET['size']) && !is_string($_GET['size'])) {
    respond(['error' => 'The size parameter must be a single tyre size.'], 400);
    exit;
}
if (isset($_GET['period']) && !is_string($_GET['period'])) {
    respond(['error' => 'The period parameter must be a single value.'], 400);
    exit;
}
try {
    $commonSizes = load_common_sizes();
    $connection = db_connection();
    $timestampColumn = observation_timestamp_column($connection);

    $rawSizeRows = $connection->query(
        'SELECT DISTINCT p.size FROM products AS p '
        . 'JOIN price_observations AS o USING (product_id) ORDER BY p.size'
    )->fetchAll();
    $rawSizesByNormalized = [];
    foreach ($rawSizeRows as $row) {
        $rawSize = (string)$row['size'];
        $normalized = normalize_size($rawSize);
        if ($normalized !== null) {
            $rawSizesByNormalized[$normalized][] = $rawSize;
        }
    }
    $availableSizes = array_keys($rawSizesByNormalized);
    sort($availableSizes, SORT_NATURAL);

    $requestedSize = isset($_GET['size']) && is_string($_GET['size'])
        ? trim($_GET['size'])
        : '';
    $selectedSize = $requestedSize !== ''
        ? $requestedSize
        : ($availableSizes[0] ?? '');
    if ($selectedSize !== '' && !in_array($selectedSize, $availableSizes, true)) {
        respond([
            'error' => 'The requested size is not available.',
            'available_sizes' => $availableSizes,
        ], 400);
        exit;
    }

    $period = isset($_GET['period']) && is_string($_GET['period'])
        ? $_GET['period']
        : 'all';
    if (!in_array($period, ['all', '7', '30', '90'], true)) {
        respond(['error' => 'The period must be all, 7, 30, or 90.'], 400);
        exit;
    }

    $commonSizeList = [];
    foreach ($availableSizes as $size) {
        $commonSizeList[] = [
            'size' => $size,
            'position' => $commonSizes[$size] ?? null,
        ];
    }

    $history = [];
    if ($selectedSize !== '') {
        $rawSizes = $rawSizesByNormalized[$selectedSize];
        $placeholders = [];
        foreach ($rawSizes as $index => $rawSize) {
            $placeholders[] = ':size' . $index;
        }
        $sql = 'SELECT p.product_id, p.name, p.size, p.url, '
            . 'o.' . $timestampColumn . ' AS observed_at, o.price_eur '
            . 'FROM products AS p JOIN price_observations AS o USING (product_id) '
            . 'WHERE p.size IN (' . implode(', ', $placeholders) . ') '
            . 'ORDER BY o.' . $timestampColumn . ', p.name';
        $statement = $connection->prepare($sql);
        foreach ($rawSizes as $index => $rawSize) {
            $statement->bindValue(':size' . $index, $rawSize, PDO::PARAM_STR);
        }
        $statement->execute();
        $history = $statement->fetchAll();
    }

    $latestByProduct = [];
    $latestTimestamp = null;
    $oldestTimestamp = null;
    foreach ($history as $index => $row) {
        $row['product_id'] = (string)$row['product_id'];
        $row['size'] = normalize_size((string)$row['size']);
        $row['price_eur'] = (float)$row['price_eur'];
        $latestByProduct[$row['product_id']] = $row;
        $timestamp = strtotime((string)$row['observed_at']);
        if ($timestamp !== false) {
            $oldestTimestamp = $oldestTimestamp === null
                ? $timestamp
                : min($oldestTimestamp, $timestamp);
            $latestTimestamp = $latestTimestamp === null
                ? $timestamp
                : max($latestTimestamp, $timestamp);
        }
        $history[$index] = $row;
    }

    $latestPrices = array_values($latestByProduct);
    usort($latestPrices, static function (array $left, array $right): int {
        $priceOrder = $left['price_eur'] <=> $right['price_eur'];
        return $priceOrder !== 0
            ? $priceOrder
            : strnatcasecmp((string)$left['name'], (string)$right['name']);
    });

    $prices = array_column($latestPrices, 'price_eur');
    $cheapest = $latestPrices[0] ?? null;
    $cutoff = $period === 'all' || $latestTimestamp === null
        ? null
        : $latestTimestamp - ((int)$period * 86400);
    $filteredHistory = [];
    foreach ($history as $row) {
        $timestamp = strtotime((string)$row['observed_at']);
        if ($timestamp === false || ($cutoff !== null && $timestamp < $cutoff)) {
            continue;
        }
        $filteredHistory[] = $row;
    }

    respond([
        'service' => 'betyre-price-data',
        'filters' => [
            'size' => $selectedSize !== '' ? $selectedSize : null,
            'period' => $period,
        ],
        'available_sizes' => $commonSizeList,
        'summary' => [
            'tyre_count' => count($latestPrices),
            'lowest_latest_price' => $cheapest,
            'median_latest_price_eur' => median($prices),
            'oldest_collection_date' => $oldestTimestamp === null
                ? null
                : date('Y-m-d', $oldestTimestamp),
            'latest_collection_date' => $latestTimestamp === null
                ? null
                : date('Y-m-d', $latestTimestamp),
            'last_collection_at' => $latestTimestamp === null
                ? null
                : date('Y-m-d H:i:s', $latestTimestamp),
        ],
        'latest_prices' => $latestPrices,
        'history' => $filteredHistory,
    ]);
} catch (PDOException $error) {
    error_log('Price service database error: ' . $error->getMessage());
    respond(['error' => 'Could not read the price database.'], 500);
} catch (JsonException $error) {
    error_log('Price service JSON error: ' . $error->getMessage());
    respond(['error' => 'Could not encode or read JSON data.'], 500);
} catch (RuntimeException $error) {
    error_log('Price service configuration error: ' . $error->getMessage());
    respond(['error' => $error->getMessage()], 500);
}
