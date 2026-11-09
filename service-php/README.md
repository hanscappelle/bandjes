# PHP JSON service

This folder exposes read-only price data from the same SQLite database used by
the dashboards. `service.php` is the unauthenticated, read-only data endpoint.
It does not start the price collector or modify the database.

## Requirements

- PHP 7.4 or newer with `PDO_SQLITE`.
- Read access to `common_motorcycle_tyre_sizes.csv` and `tyre_prices.sqlite3`
  in the project root.

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
  returned price history, using the latest observation as its reference date.

Example:

```text
GET /service.php?size=90%2F90R21&period=90
```

The response contains the available sizes and positions, summary metrics,
`latest_prices` sorted by price and `history` points for all products in the
selected size and period. Favorite state is managed by clients. Price fields
are numeric EUR values and observation timestamps are returned in the format
stored in the database. Only GET is supported; invalid parameters return HTTP
400 and operational errors return JSON with an appropriate HTTP error status.
