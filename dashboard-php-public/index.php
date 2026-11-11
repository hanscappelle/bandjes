<?php
declare(strict_types=1);

session_set_cookie_params([
    'httponly' => true,
    'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
    'samesite' => 'Lax',
]);
session_start();

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: strict-origin-when-cross-origin');
header("Content-Security-Policy: default-src 'self'; style-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'self'; frame-ancestors 'none'");

const ROOT = __DIR__ . '/..';

function h(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
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
    $path = 'common_motorcycle_tyre_sizes.csv';
    if (!is_file($path) || !is_readable($path)) {
        throw new RuntimeException('Could not read common_motorcycle_tyre_sizes.csv.');
    }

    $file = fopen($path, 'rb');
    if ($file === false) {
        throw new RuntimeException('Could not open common_motorcycle_tyre_sizes.csv.');
    }

    $header = fgetcsv($file, null, ',', '"', '\\');
    if ($header === false) {
        fclose($file);
        throw new RuntimeException('The common tyre sizes CSV is empty.');
    }

    $indexes = array_flip($header);
    if (!isset($indexes['size'], $indexes['position'])) {
        fclose($file);
        throw new RuntimeException('The common tyre sizes CSV is missing required columns.');
    }

    $sizes = [];
    while (($row = fgetcsv($file, null, ',', '"', '\\')) !== false) {
        $size = normalize_size((string)($row[$indexes['size']] ?? ''));
        if ($size !== null) {
            $sizes[$size] = (string)($row[$indexes['position']] ?? '');
        }
    }
    fclose($file);

    return $sizes;
}

function load_favorites(): array
{
    $favorites = $_SESSION['favorites'] ?? [];
    if (!is_array($favorites)) {
        throw new RuntimeException('The session favorites have an invalid format.');
    }

    foreach ($favorites as $size => $productIds) {
        if (!is_string($size) || !is_array($productIds)) {
            throw new RuntimeException('The session favorites have an invalid format.');
        }
        foreach ($productIds as $productId) {
            if (!is_string($productId)) {
                throw new RuntimeException('The session favorites have an invalid format.');
            }
        }
    }

    return $favorites;
}

function save_favorites(array $favorites): void
{
    $_SESSION['favorites'] = $favorites;
}

function db_connection(): PDO
{
    $connectionUrl = getenv('DATABASE_URL');
    $host = '';
    $port = '';
    $database = '';
    $username = '';
    $password = '';
    $sslmode = '';

    if ($connectionUrl !== false && trim($connectionUrl) !== '') {
        $parts = parse_url(trim($connectionUrl));
        if ($parts === false || !in_array($parts['scheme'] ?? '', ['postgres', 'postgresql'], true)) {
            throw new RuntimeException('DATABASE_URL must be a PostgreSQL connection URI.');
        }

        $host = (string)($parts['host'] ?? '');
        $port = isset($parts['port']) ? (string)$parts['port'] : '';
        $database = rawurldecode(ltrim((string)($parts['path'] ?? ''), '/'));
        $username = isset($parts['user']) ? rawurldecode($parts['user']) : '';
        $password = isset($parts['pass']) ? rawurldecode($parts['pass']) : '';
        $query = [];
        parse_str((string)($parts['query'] ?? ''), $query);
        $sslmode = (string)($query['sslmode'] ?? '');
    }

    $host = $host !== '' ? $host : (string)(getenv('PGHOST') ?: '');
    $port = $port !== '' ? $port : (string)(getenv('PGPORT') ?: '');
    $database = $database !== '' ? $database : (string)(getenv('PGDATABASE') ?: '');
    $username = $username !== '' ? $username : (string)(getenv('PGUSER') ?: '');
    $password = $password !== '' ? $password : (string)(getenv('PGPASSWORD') ?: '');
    $sslmode = $sslmode !== '' ? $sslmode : (string)(getenv('PGSSLMODE') ?: '');

    if ($host === '' || $database === '' || $username === '') {
        throw new RuntimeException('Configure DATABASE_URL or the PGHOST, PGDATABASE, and PGUSER environment variables.');
    }
    if ($port !== '' && !ctype_digit($port)) {
        throw new RuntimeException('PGPORT must be a valid port number.');
    }

    $dsnValue = static function (string $value): string {
        return str_replace(['\\', ';'], ['\\\\', '\\;'], $value);
    };
    $dsn = 'pgsql:host=' . $dsnValue($host)
        . ($port !== '' ? ';port=' . $port : '')
        . ';dbname=' . $dsnValue($database)
        . ($sslmode !== '' ? ';sslmode=' . $dsnValue($sslmode) : '');

    $connection = new PDO($dsn, $username, $password, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    $connection->exec('SET default_transaction_read_only = on');

    return $connection;
}

function observation_timestamp_column(PDO $connection): string
{
    $statement = $connection->prepare(
        'SELECT column_name
         FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = :table_name'
    );
    $statement->execute([':table_name' => 'price_observations']);
    $names = $statement->fetchAll(PDO::FETCH_COLUMN);
    if (in_array('observed_at', $names, true)) {
        return 'observed_at';
    }
    if (in_array('observed_on', $names, true)) {
        return 'observed_on';
    }

    throw new RuntimeException('price_observations has neither observed_at nor observed_on.');
}

function format_price(float $price): string
{
    return '€' . number_format($price, 2, ',', '.');
}

function date_label(string $value): string
{
    $timestamp = strtotime($value);
    return $timestamp === false ? $value : date('Y-m-d H:i:s', $timestamp);
}

function render_chart(array $history, array $favorites, string $period): string
{
    $latestTimestamp = 0;
    foreach ($history as $point) {
        $timestamp = strtotime($point['observed_at']);
        if ($timestamp !== false) {
            $latestTimestamp = max($latestTimestamp, $timestamp);
        }
    }

    $days = in_array($period, ['7', '30', '90'], true) ? (int)$period : null;
    $cutoff = $days === null || $latestTimestamp === 0
        ? null
        : $latestTimestamp - ($days * 86400);
    $series = [];
    foreach ($history as $point) {
        $productId = (string)$point['product_id'];
        $timestamp = strtotime($point['observed_at']);
        if (!in_array($productId, $favorites, true) || $timestamp === false) {
            continue;
        }
        if ($cutoff !== null && $timestamp < $cutoff) {
            continue;
        }
        $series[$productId]['name'] = (string)$point['name'];
        $series[$productId]['size'] = (string)$point['size'];
        $series[$productId]['product_id'] = $productId;
        $url = filter_var((string)$point['url'], FILTER_VALIDATE_URL);
        $scheme = $url === false ? '' : (string)parse_url($url, PHP_URL_SCHEME);
        if (in_array(strtolower($scheme), ['http', 'https'], true)) {
            $series[$productId]['url'] = $url;
        }
        $series[$productId]['points'][] = [
            'time' => $timestamp,
            'label' => date_label((string)$point['observed_at']),
            'price' => (float)$point['price_eur'],
        ];
    }
    $series = array_filter($series, static function (array $entry): bool {
        return !empty($entry['points']);
    });

    if (!$series) {
        return '<p class="empty">Mark tyres as favorites in the latest prices table to compare their history.</p>';
    }

    $allPoints = [];
    foreach ($series as $entry) {
        foreach ($entry['points'] as $point) {
            $allPoints[] = $point;
        }
    }
    $minTime = min(array_column($allPoints, 'time'));
    $maxTime = max(array_column($allPoints, 'time'));
    $minPrice = min(array_column($allPoints, 'price'));
    $maxPrice = max(array_column($allPoints, 'price'));
    $pricePadding = max(($maxPrice - $minPrice) * 0.1, 1);
    $minPrice -= $pricePadding;
    $maxPrice += $pricePadding;
    if ($maxTime === $minTime) {
        $minTime -= 86400;
        $maxTime += 86400;
    }

    $width = 1000;
    $height = 390;
    $left = 78;
    $right = 24;
    $top = 24;
    $bottom = 65;
    $plotWidth = $width - $left - $right;
    $plotHeight = $height - $top - $bottom;
    $colors = ['#176b55', '#d26a32', '#3569a8', '#8d5ca6', '#b44761', '#70882b', '#267f91', '#9a7629'];
    $svg = '<svg class="chart" viewBox="0 0 ' . $width . ' ' . $height . '" role="img" aria-label="Favorite tyre price history">';

    for ($tick = 0; $tick <= 4; $tick++) {
        $ratio = $tick / 4;
        $y = $top + $ratio * $plotHeight;
        $price = $maxPrice - $ratio * ($maxPrice - $minPrice);
        $svg .= '<line class="grid" x1="' . $left . '" y1="' . $y . '" x2="' . ($width - $right) . '" y2="' . $y . '"/>';
        $svg .= '<text class="axis-label" x="' . ($left - 10) . '" y="' . ($y + 4) . '" text-anchor="end">' . h(number_format($price, 2, ',', '.')) . '</text>';
    }

    for ($tick = 0; $tick <= 4; $tick++) {
        $ratio = $tick / 4;
        $x = $left + $ratio * $plotWidth;
        $timestamp = (int)($minTime + $ratio * ($maxTime - $minTime));
        $svg .= '<text class="axis-label" x="' . $x . '" y="' . ($height - 32) . '" text-anchor="middle">' . h(date('Y-m-d', $timestamp)) . '</text>';
    }

    $index = 0;
    $legend = '<ul class="chart-legend" aria-label="Favorite tyres">';
    foreach ($series as $entry) {
        $colorIndex = $index % count($colors);
        $color = $colors[$colorIndex];
        $coordinates = [];
        $points = $entry['points'];
        usort($points, static function (array $left, array $right): int {
            return $left['time'] <=> $right['time'];
        });
        $firstPrice = $points[0]['price'];
        $lastPrice = $points[count($points) - 1]['price'];
        $changeLabel = $firstPrice > 0
            ? sprintf(' (%+.1f%%)', ($lastPrice - $firstPrice) / $firstPrice * 100)
            : ' (n/a)';
        foreach ($points as $point) {
            $x = $left + (($point['time'] - $minTime) / ($maxTime - $minTime)) * $plotWidth;
            $y = $top + (($maxPrice - $point['price']) / ($maxPrice - $minPrice)) * $plotHeight;
            $coordinates[] = [$x, $y, $point];
        }
        if (count($coordinates) > 1) {
            $path = [];
            foreach ($coordinates as $coordinate) {
                $path[] = ($path ? 'L' : 'M') . round($coordinate[0], 2) . ' ' . round($coordinate[1], 2);
            }
            $svg .= '<path class="series-line" stroke="' . $color . '" d="' . implode(' ', $path) . '"/>';
        }
        foreach ($coordinates as $coordinate) {
            $tooltip = $entry['name'] . ' (' . $entry['size'] . ') — ' . $coordinate[2]['label'] . ' — ' . format_price($coordinate[2]['price']);
            $svg .= '<circle class="point" cx="' . round($coordinate[0], 2) . '" cy="' . round($coordinate[1], 2) . '" r="4" fill="' . $color . '"><title>' . h($tooltip) . '</title></circle>';
        }
        $productNumber = '#' . h($entry['product_id']);
        if (!empty($entry['url'])) {
            $productNumber = '<a href="' . h($entry['url']) . '" target="_blank" rel="noopener noreferrer">' . $productNumber . '</a>';
        }
        $legend .= '<li><svg class="legend-swatch" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><circle cx="5" cy="5" r="5" fill="' . h($color) . '"/></svg><span>' . h($entry['name']) . ' (' . $productNumber . ')' . h($changeLabel) . '</span></li>';
        $index++;
    }

    return $svg . '</svg>' . $legend . '</ul>';
}

$errorMessage = '';
$selectedSize = '';
$filterTerm = isset($_GET['filter']) && is_string($_GET['filter'])
    ? trim($_GET['filter'])
    : '';
$favoritesOnly = isset($_GET['favorites_only']) && $_GET['favorites_only'] === '1';
$period = isset($_GET['period']) && in_array((string)$_GET['period'], ['7', '30', '90'], true)
    ? (string)$_GET['period']
    : 'all';
$commonSizes = [];
$availableSizes = [];
$history = [];
$latest = [];
$favoritesBySize = [];
$connection = null;

try {
    $commonSizes = load_common_sizes();
    $connection = db_connection();
    $timestampColumn = observation_timestamp_column($connection);

    $sizeRows = $connection->query(
        'SELECT DISTINCT p.size FROM products AS p JOIN price_observations AS o USING (product_id) ORDER BY p.size'
    )->fetchAll();
    foreach ($sizeRows as $row) {
        $normalized = normalize_size((string)$row['size']);
        if ($normalized !== null) {
            $availableSizes[$normalized] = true;
        }
    }
    $availableSizes = array_keys($availableSizes);
    sort($availableSizes, SORT_NATURAL);

    $requestedSize = isset($_GET['size']) ? (string)$_GET['size'] : '';
    $selectedSize = in_array($requestedSize, $availableSizes, true)
        ? $requestedSize
        : ($availableSizes[0] ?? '');

    $favoritesBySize = load_favorites();
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $postedToken = (string)($_POST['csrf_token'] ?? '');
        if (!isset($_SESSION['csrf_token']) || !hash_equals($_SESSION['csrf_token'], $postedToken)) {
            throw new RuntimeException('The form expired or could not be verified. Reload the page and try again.');
        }

        $postedSize = (string)($_POST['size'] ?? '');
        $productId = (string)($_POST['product_id'] ?? '');
        if (!in_array($postedSize, $availableSizes, true) || $productId === '') {
            throw new RuntimeException('Invalid favorite selection.');
        }

        $productCheck = $connection->prepare(
            'SELECT size FROM products WHERE product_id = :product_id'
        );
        $productCheck->execute([':product_id' => $productId]);
        $productSize = $productCheck->fetchColumn();
        if ($productSize === false || normalize_size((string)$productSize) !== $postedSize) {
            throw new RuntimeException('That tyre is not present for the selected size.');
        }

        $current = $favoritesBySize[$postedSize] ?? [];
        $current = array_values(array_unique(array_filter($current, 'is_string')));
        if (in_array($productId, $current, true)) {
            $current = array_values(array_diff($current, [$productId]));
        } else {
            $current[] = $productId;
        }
        $favoritesBySize[$postedSize] = $current;
        save_favorites($favoritesBySize);

        $redirectPeriod = in_array((string)($_POST['period'] ?? ''), ['all', '7', '30', '90'], true)
            ? (string)$_POST['period']
            : 'all';
        $filterTerm = isset($_POST['filter']) && is_string($_POST['filter'])
            ? trim($_POST['filter'])
            : '';
        $favoritesOnly = isset($_POST['favorites_only'])
            && $_POST['favorites_only'] === '1';
        $redirectParameters = [
            'size' => $postedSize,
            'period' => $redirectPeriod,
        ];
        if ($filterTerm !== '') {
            $redirectParameters['filter'] = $filterTerm;
        }
        if ($favoritesOnly) {
            $redirectParameters['favorites_only'] = '1';
        }
        $redirectUrl = '?' . http_build_query($redirectParameters) . '#product-' . rawurlencode($productId);
        header('Location: ' . $redirectUrl, true, 303);
        exit;
    }

    if (!isset($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }

    if ($selectedSize !== '') {
        $query = $connection->prepare(
            'SELECT p.product_id, p.name, p.size, p.url, o.' . $timestampColumn . ' AS observed_at, o.price_eur
             FROM products AS p JOIN price_observations AS o USING (product_id)
             ORDER BY o.' . $timestampColumn . ', p.name'
        );
        $query->execute();
        foreach ($query as $row) {
            if (normalize_size((string)$row['size']) !== $selectedSize || !is_numeric($row['price_eur'])) {
                continue;
            }
            $row['product_id'] = (string)$row['product_id'];
            $row['price_eur'] = (float)$row['price_eur'];
            $history[] = $row;
            $existing = $latest[$row['product_id']] ?? null;
            if ($existing === null || strcmp((string)$row['observed_at'], (string)$existing['observed_at']) > 0) {
                $latest[$row['product_id']] = $row;
            }
        }
        uasort($latest, static function (array $left, array $right): int {
            return ($left['price_eur'] <=> $right['price_eur']) ?: strcasecmp((string)$left['name'], (string)$right['name']);
        });
    }
} catch (PDOException | RuntimeException | JsonException $error) {
    $errorMessage = $error->getMessage();
}

if ($connection instanceof PDO) {
    $connection = null;
}

$savedFavorites = array_values(array_intersect(
    array_map('strval', $favoritesBySize[$selectedSize] ?? []),
    array_keys($latest)
));
$historyTimes = array_filter(array_map(
    static function (array $row) {
        return strtotime((string)$row['observed_at']);
    },
    $history
), static function ($value): bool {
    return $value !== false;
});
$oldestDate = $historyTimes ? date('Y-m-d', min($historyTimes)) : '';
$newestDate = $historyTimes ? date('Y-m-d', max($historyTimes)) : '';
$lastCollection = $historyTimes ? date_label((string)$history[count($history) - 1]['observed_at']) : '';
$medianPrice = null;
if ($latest) {
    $prices = array_column($latest, 'price_eur');
    sort($prices, SORT_NUMERIC);
    $middle = intdiv(count($prices), 2);
    $medianPrice = count($prices) % 2
        ? $prices[$middle]
        : ($prices[$middle - 1] + $prices[$middle]) / 2;
}
?>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Betyre Motorcycle Tyre Price Watch</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
<div class="page">
    <header class="site-header">
        <div class="brand-mark" aria-hidden="true">🏍</div>
        <div>
            <p class="eyebrow">PERSONAL PRICE HISTORY</p>
            <h1>Betyre Motorcycle Tyre Price Watch</h1>
            <p class="subtitle">Price history collected from bandenleader.be. This dashboard is not affiliated with the shop.</p>
        </div>
    </header>

    <?php if ($errorMessage !== ''): ?>
        <div class="alert error" role="alert"><strong>Dashboard error:</strong> <?= h($errorMessage) ?></div>
    <?php elseif (!$availableSizes): ?>
        <div class="alert">No recorded tyre sizes yet. Run the PostgreSQL collector to populate the database.</div>
    <?php else: ?>
        <main>
            <section class="panel controls">
                <form method="get" class="filters">
                    <input type="hidden" name="filter" id="results-filter-query" value="<?= h($filterTerm) ?>">
                    <input type="hidden" name="favorites_only" id="results-filter-favorites-only" value="<?= $favoritesOnly ? '1' : '0' ?>">
                    <label>
                        Tyre size
                        <select name="size">
                            <?php foreach ($availableSizes as $size): ?>
                                <?php $position = $commonSizes[$size] ?? 'Position unknown'; ?>
                                <option value="<?= h($size) ?>" <?= $size === $selectedSize ? 'selected' : '' ?>>
                                    <?= h($size . ' (' . $position . ')') ?>
                                </option>
                            <?php endforeach; ?>
                        </select>
                    </label>
                    <label>
                        History period
                        <select name="period">
                            <option value="all" <?= $period === 'all' ? 'selected' : '' ?>>All history</option>
                            <option value="90" <?= $period === '90' ? 'selected' : '' ?>>Last 90 days</option>
                            <option value="30" <?= $period === '30' ? 'selected' : '' ?>>Last 30 days</option>
                            <option value="7" <?= $period === '7' ? 'selected' : '' ?>>Last 7 days</option>
                        </select>
                    </label>
                </form>
            </section>

            <?php if (!$latest): ?>
                <div class="alert">No price observations found for <?= h($selectedSize) ?>.</div>
            <?php else: ?>
                <?php $cheapest = reset($latest); ?>
                <section class="panel chart-panel">
                    <div class="section-heading">
                        <div>
                            <p class="eyebrow">FAVORITES</p>
                            <h2>Price history</h2>
                        </div>
                        <span class="count"><?= $period === 'all' ? 'All history' : 'Last ' . h($period) . ' days' ?></span>
                    </div>
                    <?= render_chart($history, $savedFavorites, $period) ?>
                    <p class="hint chart-note">A missing observation means the tyre was not present in that day's collected results; it does not necessarily mean it was out of stock. Hover over chart points for details.</p>
                </section>

                <section class="metrics" aria-label="Price summary">
                    <article class="metric panel">
                        <span class="metric-label">Tyres with recorded prices</span>
                        <strong><?= number_format(count($latest)) ?></strong>
                    </article>
                    <article class="metric panel">
                        <span class="metric-label">Lowest latest price</span>
                        <strong><?= h(format_price((float)$cheapest['price_eur'])) ?></strong>
                        <small><?= h((string)$cheapest['name']) ?></small>
                    </article>
                    <article class="metric panel">
                        <span class="metric-label">Median latest price</span>
                        <strong><?= h(format_price((float)$medianPrice)) ?></strong>
                    </article>
                </section>

                <p class="collection-note">
                    Collection dates: <?= h($oldestDate) ?> to <?= h($newestDate) ?>.
                    Latest collection for <?= h($selectedSize) ?>: <?= h($lastCollection) ?>.
                    Each price shown is the most recently recorded for that tyre.
                </p>

                <section class="panel">
                    <div class="section-heading">
                        <div>
                            <p class="eyebrow">CURRENT SNAPSHOT</p>
                            <h2>Latest recorded prices</h2>
                        </div>
                        <span class="count" id="latest-results-count" data-total="<?= count($latest) ?>"><?= count($latest) ?> tyres</span>
                    </div>
                    <p class="hint">Use the star to add or remove a tyre from the price-history chart.</p>
                    <div class="results-filters">
                        <label class="table-filter">
                            Filter results
                            <input
                                id="latest-results-filter"
                                type="search"
                                name="filter"
                                value="<?= h($filterTerm) ?>"
                                placeholder="Search tyre, price, or date"
                                aria-controls="latest-results-table"
                            >
                        </label>
                        <label class="favorites-filter">
                            <input
                                id="latest-results-favorites-only"
                                type="checkbox"
                                <?= $favoritesOnly ? 'checked' : '' ?>
                                aria-controls="latest-results-table"
                            >
                            Favorites only
                        </label>
                    </div>
                    <div class="table-wrap">
                        <table id="latest-results-table">
                            <thead>
                            <tr>
                                <th scope="col" aria-label="Favorite"></th>
                                <th aria-sort="none"><button class="sort-button" type="button" data-sort-column="1">Tyre</button></th>
                                <th class="numeric" aria-sort="none"><button class="sort-button" type="button" data-sort-column="2">Price</button></th>
                                <th aria-sort="none"><button class="sort-button" type="button" data-sort-column="3">Last seen</button></th>
                                <th scope="col" aria-label="Product page"></th>
                            </tr>
                            </thead>
                            <tbody>
                            <?php foreach ($latest as $productId => $row): ?>
                                <?php
                                $isFavorite = in_array((string)$productId, $savedFavorites, true);
                                $url = filter_var((string)$row['url'], FILTER_VALIDATE_URL);
                                $scheme = $url === false ? '' : (string)parse_url($url, PHP_URL_SCHEME);
                                $safeUrl = in_array(strtolower($scheme), ['http', 'https'], true) ? $url : '';
                                ?>
                                <tr id="product-<?= h((string)$productId) ?>" data-favorite="<?= $isFavorite ? '1' : '0' ?>">
                                    <td data-sort-value="<?= $isFavorite ? '1' : '0' ?>">
                                        <form method="post" class="favorite-form">
                                            <input type="hidden" name="csrf_token" value="<?= h((string)$_SESSION['csrf_token']) ?>">
                                            <input type="hidden" name="size" value="<?= h($selectedSize) ?>">
                                            <input type="hidden" name="period" value="<?= h($period) ?>">
                                            <input type="hidden" name="filter" value="<?= h($filterTerm) ?>">
                                            <input type="hidden" name="favorites_only" value="<?= $favoritesOnly ? '1' : '0' ?>">
                                            <input type="hidden" name="product_id" value="<?= h((string)$productId) ?>">
                                            <button class="star <?= $isFavorite ? 'active' : '' ?>" type="submit" aria-label="<?= $isFavorite ? 'Remove favorite' : 'Add favorite' ?>" title="<?= $isFavorite ? 'Remove favorite' : 'Add favorite' ?>">
                                                <?= $isFavorite ? '★' : '☆' ?>
                                            </button>
                                        </form>
                                    </td>
                                    <td class="tyre-name"><?= h((string)$row['name']) ?></td>
                                    <td class="numeric price" data-sort-value="<?= h((string)$row['price_eur']) ?>"><?= h(format_price((float)$row['price_eur'])) ?></td>
                                    <td class="timestamp" data-sort-value="<?= h((string)$row['observed_at']) ?>"><?= h(date_label((string)$row['observed_at'])) ?></td>
                                    <td>
                                        <?php if ($safeUrl !== ''): ?>
                                            <a href="<?= h($safeUrl) ?>" target="_blank" rel="noopener noreferrer" data-sort-value="<?= h($safeUrl) ?>">Open ↗</a>
                                        <?php else: ?>
                                            <span class="muted">Unavailable</span>
                                        <?php endif; ?>
                                    </td>
                                </tr>
                            <?php endforeach; ?>
                            <tr id="latest-results-empty" hidden>
                                <td colspan="5">No tyres match these filters.</td>
                            </tr>
                            </tbody>
                        </table>
                    </div>
                </section>
            <?php endif; ?>
        </main>
    <?php endif; ?>

    <footer>
        <span>Betyre Motorcycle Tyre Price Watch</span>
        <span>The database is read-only to this dashboard. Favourites are stored in session cookies.</span>
        <a href="https://github.com/hanscappelle/bandjes" target="_blank" rel="noopener noreferrer">
            Feature requests and bug reports
        </a>
    </footer>
</div>
<script src="dashboard.js" defer></script>
</body>
</html>
