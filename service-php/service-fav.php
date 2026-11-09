<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

const ROOT = __DIR__ . '/..';
const FAVORITES_FILE = ROOT . '/dashboard-php/favorites.json';
const FAVORITES_LOCK_FILE = ROOT . '/dashboard-php/favorites.lock';
const API_CONFIG_FILE = ROOT . '/.bandjes-api-config.php';
const MAX_REQUEST_BYTES = 4096;

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

function bearer_token(): string
{
    $authorization = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($authorization === '' && function_exists('getallheaders')) {
        $headers = getallheaders();
        $authorization = is_array($headers) ? (string)($headers['Authorization'] ?? '') : '';
    }

    if (!preg_match('/^Bearer ([A-Za-z0-9._~-]+)$/D', $authorization, $matches)) {
        return '';
    }

    return $matches[1];
}

function configured_api_token(): string
{
    if (!is_file(API_CONFIG_FILE) || !is_readable(API_CONFIG_FILE)) {
        throw new RuntimeException('The API token config file is missing or unreadable.');
    }

    try {
        $config = require API_CONFIG_FILE;
    } catch (Throwable $error) {
        error_log('Favorite API config could not be loaded: ' . $error->getMessage());
        throw new RuntimeException('The API token config file could not be loaded.');
    }

    if (!is_array($config)
        || !isset($config['api_token'])
        || !is_string($config['api_token'])
        || strlen($config['api_token']) < 32
    ) {
        throw new RuntimeException('The API token config must return an api_token of at least 32 characters.');
    }

    return $config['api_token'];
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

function load_favorites(): array
{
    if (!is_file(FAVORITES_FILE)) {
        return [];
    }
    if (!is_readable(FAVORITES_FILE)) {
        throw new RuntimeException('The favorites file is not readable.');
    }

    $contents = file_get_contents(FAVORITES_FILE);
    if ($contents === false) {
        throw new RuntimeException('Could not read the favorites file.');
    }
    $favorites = json_decode($contents, true, 512, JSON_THROW_ON_ERROR);
    if (!is_array($favorites)) {
        throw new RuntimeException('The favorites file has an invalid format.');
    }

    foreach ($favorites as $size => $productIds) {
        if (!is_string($size) || !is_array($productIds)) {
            throw new RuntimeException('The favorites file has an invalid format.');
        }
        foreach ($productIds as $productId) {
            if (!is_string($productId)) {
                throw new RuntimeException('The favorites file has an invalid format.');
            }
        }
    }

    return $favorites;
}

function save_favorites(array $favorites): void
{
    $json = json_encode($favorites, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR) . "\n";
    $temporaryPath = tempnam(dirname(FAVORITES_FILE), '.favorites-');
    if ($temporaryPath === false) {
        throw new RuntimeException('Could not create a temporary favorites file.');
    }

    try {
        if (file_put_contents($temporaryPath, $json, LOCK_EX) === false) {
            throw new RuntimeException('Could not write the favorites file.');
        }
        chmod($temporaryPath, 0600);
        if (!rename($temporaryPath, FAVORITES_FILE)) {
            throw new RuntimeException('Could not replace the favorites file.');
        }
    } finally {
        if (is_file($temporaryPath)) {
            unlink($temporaryPath);
        }
    }
}

function acquire_favorites_lock()
{
    $lock = fopen(FAVORITES_LOCK_FILE, 'c');
    if ($lock === false) {
        throw new RuntimeException('Could not open the favorites lock file.');
    }
    if (!flock($lock, LOCK_EX)) {
        fclose($lock);
        throw new RuntimeException('Could not lock the favorites file.');
    }

    return $lock;
}

function request_payload(): array
{
    $contentType = strtolower(trim(explode(';', $_SERVER['CONTENT_TYPE'] ?? '')[0]));
    if ($contentType !== 'application/json') {
        respond(['error' => 'Content-Type must be application/json.'], 415);
        exit;
    }

    $contentLength = isset($_SERVER['CONTENT_LENGTH']) ? (int)$_SERVER['CONTENT_LENGTH'] : null;
    if ($contentLength !== null && $contentLength > MAX_REQUEST_BYTES) {
        respond(['error' => 'Request body is too large.'], 413);
        exit;
    }

    $body = file_get_contents('php://input', false, null, 0, MAX_REQUEST_BYTES + 1);
    if ($body === false) {
        throw new RuntimeException('Could not read the request body.');
    }
    if (strlen($body) > MAX_REQUEST_BYTES) {
        respond(['error' => 'Request body is too large.'], 413);
        exit;
    }

    try {
        $decoded = json_decode($body, false, 16, JSON_THROW_ON_ERROR);
    } catch (JsonException $error) {
        respond(['error' => 'The request body must contain valid JSON.'], 400);
        exit;
    }
    if (!$decoded instanceof stdClass) {
        respond(['error' => 'The request body must be a JSON object.'], 400);
        exit;
    }

    $payload = get_object_vars($decoded);
    if (!is_array($payload)
        || !isset($payload['size'], $payload['product_id'], $payload['favorite'])
        || !is_string($payload['size'])
        || !is_string($payload['product_id'])
        || !is_bool($payload['favorite'])
        || count($payload) !== 3
    ) {
        respond([
            'error' => 'Expected JSON with string size, string product_id, and boolean favorite.',
        ], 400);
        exit;
    }

    return $payload;
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    header('Allow: POST');
    respond(['error' => 'Only POST requests are supported.'], 405);
    exit;
}
if (empty($_SERVER['HTTPS']) || strtolower((string)$_SERVER['HTTPS']) === 'off') {
    respond(['error' => 'HTTPS is required.'], 400);
    exit;
}

try {
    $configuredToken = configured_api_token();
} catch (RuntimeException $error) {
    error_log('Favorite API configuration error: ' . $error->getMessage());
    respond(['error' => 'Favorite updates are not configured on the server.'], 503);
    exit;
}
if (!hash_equals($configuredToken, bearer_token())) {
    respond(['error' => 'A valid bearer token is required.'], 401);
    exit;
}

try {
    $payload = request_payload();
    $size = normalize_size($payload['size']);
    $productId = trim($payload['product_id']);
    if ($size === null || $size !== $payload['size'] || $productId === '' || strlen($productId) > 128) {
        respond(['error' => 'The size or product_id is invalid.'], 400);
        exit;
    }

    $connection = db_connection();
    $productCheck = $connection->prepare(
        'SELECT size FROM products WHERE product_id = :product_id'
    );
    $productCheck->execute([':product_id' => $productId]);
    $productSize = $productCheck->fetchColumn();
    if ($productSize === false || normalize_size((string)$productSize) !== $size) {
        respond(['error' => 'The product does not belong to the requested size.'], 404);
        exit;
    }

    $lock = acquire_favorites_lock();
    try {
        $favorites = load_favorites();
        $sizeFavorites = array_values(array_unique($favorites[$size] ?? []));
        if ($payload['favorite']) {
            if (!in_array($productId, $sizeFavorites, true)) {
                $sizeFavorites[] = $productId;
            }
        } else {
            $sizeFavorites = array_values(array_diff($sizeFavorites, [$productId]));
        }
        $favorites[$size] = $sizeFavorites;
        save_favorites($favorites);
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }

    respond([
        'size' => $size,
        'product_id' => $productId,
        'favorite' => $payload['favorite'],
    ]);
} catch (PDOException $error) {
    error_log('Favorite API database error: ' . $error->getMessage());
    respond(['error' => 'Could not validate the product.'], 500);
} catch (JsonException $error) {
    error_log('Favorite API favorites file error: ' . $error->getMessage());
    respond(['error' => 'Could not read or write the favorites file.'], 500);
} catch (RuntimeException $error) {
    error_log('Favorite API configuration error: ' . $error->getMessage());
    respond(['error' => $error->getMessage()], 500);
}
