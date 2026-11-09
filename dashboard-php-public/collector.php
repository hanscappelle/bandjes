<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const MAX_BODY_BYTES = 2097152;
const MAX_PRODUCTS = 2000;

function respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_SLASHES);
    exit;
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

function collector_db_connection(): PDO
{
    $connectionUrl = getenv('COLLECTOR_DATABASE_URL');
    if ($connectionUrl === false || trim($connectionUrl) === '') {
        throw new RuntimeException('COLLECTOR_DATABASE_URL is not configured.');
    }

    $parts = parse_url(trim($connectionUrl));
    if (
        $parts === false
        || !in_array($parts['scheme'] ?? '', ['postgres', 'postgresql'], true)
        || empty($parts['host'])
        || empty($parts['user'])
        || empty($parts['path'])
    ) {
        throw new RuntimeException('COLLECTOR_DATABASE_URL is not a valid PostgreSQL URI.');
    }

    $query = [];
    parse_str((string)($parts['query'] ?? ''), $query);
    $host = rawurldecode((string)$parts['host']);
    $database = rawurldecode(ltrim((string)$parts['path'], '/'));
    $username = rawurldecode((string)$parts['user']);
    $password = rawurldecode((string)($parts['pass'] ?? ''));
    $port = isset($parts['port']) ? (string)$parts['port'] : '';
    $sslmode = (string)($query['sslmode'] ?? '');

    if ($database === '' || ($port !== '' && (!ctype_digit($port) || (int)$port > 65535))) {
        throw new RuntimeException('COLLECTOR_DATABASE_URL has invalid database settings.');
    }

    $dsnValue = static function (string $value): string {
        return str_replace(['\\', ';'], ['\\\\', '\\;'], $value);
    };
    $dsn = 'pgsql:host=' . $dsnValue($host)
        . ($port !== '' ? ';port=' . $port : '')
        . ';dbname=' . $dsnValue($database)
        . ($sslmode !== '' ? ';sslmode=' . $dsnValue($sslmode) : '');

    return new PDO($dsn, $username, $password, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Allow: POST');
    respond(405, ['ok' => false, 'error' => 'Only POST is supported.']);
}

$isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || (($_SERVER['REQUEST_SCHEME'] ?? '') === 'https');
if (!$isHttps) {
    respond(400, ['ok' => false, 'error' => 'HTTPS is required.']);
}

$expectedToken = getenv('PUBLISH_TOKEN');
if ($expectedToken === false || strlen($expectedToken) < 32) {
    error_log('Collector endpoint is missing PUBLISH_TOKEN.');
    respond(503, ['ok' => false, 'error' => 'Collector endpoint is not configured.']);
}
$authorization = (string)($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
if (!preg_match('/^Bearer\s+(.+)$/i', $authorization, $matches)
    || !hash_equals($expectedToken, $matches[1])) {
    respond(401, ['ok' => false, 'error' => 'Invalid bearer token.']);
}

$contentType = strtolower(trim(explode(';', (string)($_SERVER['CONTENT_TYPE'] ?? ''))[0]));
if ($contentType !== 'application/json') {
    respond(415, ['ok' => false, 'error' => 'Content-Type must be application/json.']);
}

$contentLength = (string)($_SERVER['CONTENT_LENGTH'] ?? '');
if ($contentLength !== '' && ctype_digit($contentLength) && (int)$contentLength > MAX_BODY_BYTES) {
    respond(413, ['ok' => false, 'error' => 'Request body is too large.']);
}
$body = file_get_contents('php://input', false, null, 0, MAX_BODY_BYTES + 1);
if ($body === false || strlen($body) > MAX_BODY_BYTES) {
    respond(413, ['ok' => false, 'error' => 'Request body is too large.']);
}

try {
    $payload = json_decode($body, true, 32, JSON_THROW_ON_ERROR);
} catch (JsonException $error) {
    respond(400, ['ok' => false, 'error' => 'Request body must be valid JSON.']);
}

if (!is_array($payload)
    || !isset($payload['observed_at'], $payload['products'])
    || !is_string($payload['observed_at'])
    || !is_array($payload['products'])
    || count($payload['products']) < 1
    || count($payload['products']) > MAX_PRODUCTS) {
    respond(400, ['ok' => false, 'error' => 'Payload must include a timestamp and 1 to 2000 products.']);
}

$timestamp = DateTimeImmutable::createFromFormat(
    '!Y-m-d H:i:s',
    $payload['observed_at'],
    new DateTimeZone('UTC')
);
if ($timestamp === false || $timestamp->format('Y-m-d H:i:s') !== $payload['observed_at']) {
    respond(400, ['ok' => false, 'error' => 'observed_at must use YYYY-MM-DD HH:MM:SS format.']);
}

$products = [];
foreach ($payload['products'] as $product) {
    if (!is_array($product)
        || !isset($product['id'], $product['name'], $product['size'], $product['url'], $product['price'])
        || !is_string($product['id'])
        || !is_string($product['name'])
        || !is_string($product['size'])
        || !is_string($product['url'])
        || (!is_string($product['price']) && !is_int($product['price']) && !is_float($product['price']))) {
        respond(400, ['ok' => false, 'error' => 'A product has missing or invalid fields.']);
    }

    $size = normalize_size($product['size']);
    $price = (string)$product['price'];
    if ($product['id'] === '' || strlen($product['id']) > 255
        || strlen($product['name']) > 500
        || $size !== $product['size']
        || strlen($product['url']) > 2048
        || filter_var($product['url'], FILTER_VALIDATE_URL) === false
        || parse_url($product['url'], PHP_URL_SCHEME) !== 'https'
        || !preg_match('/^\d{1,10}(?:\.\d{1,4})?$/', $price)
        || (float)$price <= 0) {
        respond(400, ['ok' => false, 'error' => 'A product contains invalid values.']);
    }

    $products[] = [
        'id' => $product['id'],
        'name' => $product['name'],
        'size' => $size,
        'url' => $product['url'],
        'price' => $price,
    ];
}

try {
    $connection = collector_db_connection();
    $connection->beginTransaction();
    $productStatement = $connection->prepare(
        'INSERT INTO products (product_id, name, size, url)
         VALUES (:product_id, :name, :size, :url)
         ON CONFLICT (product_id) DO UPDATE SET
             name = EXCLUDED.name, size = EXCLUDED.size, url = EXCLUDED.url'
    );
    $observationStatement = $connection->prepare(
        'INSERT INTO price_observations (observed_at, product_id, price_eur)
         VALUES (:observed_at, :product_id, :price_eur)
         ON CONFLICT (observed_at, product_id)
         DO UPDATE SET price_eur = EXCLUDED.price_eur'
    );

    foreach ($products as $product) {
        $productStatement->execute([
            ':product_id' => $product['id'],
            ':name' => $product['name'],
            ':size' => $product['size'],
            ':url' => $product['url'],
        ]);
        $observationStatement->execute([
            ':observed_at' => $payload['observed_at'],
            ':product_id' => $product['id'],
            ':price_eur' => $product['price'],
        ]);
    }
    $connection->commit();
} catch (Throwable $error) {
    if (isset($connection) && $connection instanceof PDO && $connection->inTransaction()) {
        try {
            $connection->rollBack();
        } catch (Throwable $rollbackError) {
            error_log('Collector publish rollback failed: ' . $rollbackError->getMessage());
        }
    }
    error_log('Collector publish failed: ' . $error->getMessage());
    respond(500, ['ok' => false, 'error' => 'Could not save observations.']);
}

respond(200, ['ok' => true, 'saved' => count($products)]);
