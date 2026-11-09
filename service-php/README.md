# PHP JSON service

This folder exposes read-only price data from the same SQLite database used by
the dashboards. `service.php` is the unauthenticated, read-only data endpoint.
`service-fav.php` is a separate authenticated endpoint for updating favorites.
Neither endpoint starts the price collector or modifies the database.

## Requirements

- PHP 7.4 or newer with `PDO_SQLITE`.
- Read access to `common_motorcycle_tyre_sizes.csv` and `tyre_prices.sqlite3`
  in the project root.
- The service uses the same favorites saved by `dashboard-php`. The service
  account needs read access to `dashboard-php/favorites.json`. Both the web
  dashboard and service account need write access to that folder and its
  `favorites.lock` file to update favorites safely.

Set `TYRE_PRICE_DB` to the absolute SQLite database path if it is not at the
project root. Configure the web server document root to `service-php` and send
GET requests to `service.php`.

## Endpoint

`GET /service.php`

Optional query parameters:

- `size`: canonical tyre size, such as `90/90R21`. Defaults to the first
  available size. An unavailable size returns HTTP 400 and the available
  choices.
- `period`: `all`, `7`, `30`, or `90`. Defaults to `all`; the period limits
  returned favorite history, using the latest observation as its reference
  date.

Example:

```text
GET /service.php?size=90%2F90R21&period=90
```

The response contains the available sizes and positions, summary metrics,
`latest_prices` sorted by price, current `favorites`, and `history` points for
those favorites. Price fields are numeric EUR values and observation timestamps
are returned in the format stored in the database. Only GET is supported;
invalid parameters return HTTP 400 and operational errors return JSON with an
appropriate HTTP error status.

## Favorite update endpoint

`POST /service-fav.php` sets one product's favorite state for a tyre size. It
updates the same `dashboard-php/favorites.json` file used by the web dashboard.
The operation is idempotent, so repeating the same request has the same result.

Configure the private token in `.bandjes-api-config.php` at the project root,
one directory above `service-php`. This keeps it outside the Web Station
document root. The config must return an array with an `api_token` string:

```php
<?php
return [
    'api_token' => 'replace-with-a-random-token',
];
```

The config filename is ignored by Git. Generate a random token with
`openssl rand -hex 32`, then set the same value in this config and in app
settings. Keep the token private; do not put it in a URL or hard-code it in an
APK. The Android app encrypts its saved token using the Android Keystore.

On Synology, place `.bandjes-api-config.php` in the project directory above
`service-php` (alongside the database and CSV), not inside the Web Station
document root. Ensure the Web Station PHP account can read the file, and restrict
write access to administrators.

The endpoint accepts only HTTPS POST requests with a bearer token and a JSON
body:

```http
POST /service-fav.php
Authorization: Bearer <BANDJES_API_TOKEN>
Content-Type: application/json
```

```json
{
  "size": "90/90R21",
  "product_id": "product-id",
  "favorite": true
}
```

The service checks the token, validates that the product exists in the database
and belongs to the supplied size, then atomically updates the favorites file.
It returns the updated size, product ID, and favorite state. Missing or invalid
server configuration returns HTTP 503; an invalid token returns HTTP 401.
