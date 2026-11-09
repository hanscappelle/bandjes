# PostgreSQL PHP dashboard

This folder is a separate PHP dashboard that reads the PostgreSQL database
populated by `collector/scrape_prices.py`. It shows the latest prices and
collection summary, supports favorite tyres, and draws selected favorites'
price history. The SQLite dashboard in `dashboard-php` remains unchanged.
Favorites are stored in the visitor's PHP session, not in a shared file, so
each browser session has its own selections. They are temporary and can be
lost when the session expires or the visitor clears their cookies.

The dashboard connects using `DATABASE_URL` or the standard `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, and `PGPASSWORD` environment variables. It does not
create or modify database tables; initialize the `products` and
`price_observations` tables once using [`collector/schema.sql`](../collector/schema.sql).
The read-only JSON endpoint in `service.php` uses the same database settings
and provides all price history to clients such as the Android app; favorite
state is managed by each client.
Use the **Feature requests and bug reports** link in the dashboard footer or
visit the [public GitHub repository](https://github.com/hanscappelle/bandjes).

The `collector.php` endpoint accepts authenticated HTTPS POST requests from
the NAS collector and inserts/upserts price observations. It uses
`COLLECTOR_DATABASE_URL` and `PUBLISH_TOKEN`, which should be configured as
server-side secrets. Keep `DATABASE_URL` on a read-only role for the dashboard,
and use a separate writer role for `COLLECTOR_DATABASE_URL`.

## Requirements

- PHP 7.4 or newer, with `PDO_PGSQL` enabled.
- A PostgreSQL database populated by the collector. See
  [`collector/README.md`](../collector/README.md) for collector setup.
- The project root's `common_motorcycle_tyre_sizes.csv`.
- Network access from the PHP host to PostgreSQL.
- A web-server account that can read the project files and use PHP sessions.

## Configure and deploy

1. Configure the PHP service with either:

   - `DATABASE_URL`, a PostgreSQL URI such as
     `postgresql://dashboard:password@db.example.com:5432/bandjes?sslmode=require`;
     or
   - `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD`. `PGPORT` is
     optional and defaults to the PostgreSQL driver's default port.

   Store credentials in the hosting provider's secret or environment-variable
   settings. Do not commit credentials to this repository. For production,
   use a PostgreSQL role with read-only privileges on the two dashboard tables.

2. Configure the web server's document root to `dashboard-php-public`. Keep
   that folder alongside `common_motorcycle_tyre_sizes.csv`; the dashboard and
   `service.php` read the CSV from the project root.

3. Enable the PHP PostgreSQL PDO extension (`PDO_PGSQL`) in the PHP profile
   used by the web server. Ensure that the PHP service can connect to the
   configured database host.

4. Ensure PHP sessions are enabled for the site. Database collection remains
   a separate task run by `collector/scrape_prices.py`.

5. To receive NAS publishing, deploy `collector.php` in the same document root
   as `index.php`. Configure `PUBLISH_TOKEN` to a random secret and
   `COLLECTOR_DATABASE_URL` to a dedicated writer account. Initialize the
   database tables once using [`collector/schema.sql`](../collector/schema.sql),
   then grant the writer role `USAGE` on the schema and `SELECT`, `INSERT`, and
   `UPDATE` on `products` and `price_observations`. The endpoint does not create
   tables or allow unauthenticated writes.

6. On the NAS, set `PUBLISH_URL` to the HTTPS URL ending in `/collector.php`
   and `PUBLISH_TOKEN` to the same secret. Run the collector with
   `python3 collector/scrape_prices.py ...`; this publishing mode uses only
   Python's standard library and does not require `psycopg`.

## Security

The dashboard has no user login. The `public` folder name does not add access
control: do not expose this page to the public internet unless you separately
configure authentication and appropriate network restrictions. Keep database
credentials in server-side environment settings, and use a read-only database
role for the dashboard.

## Troubleshooting

- A PDO driver error means the PHP profile needs its `PDO_PGSQL` extension
  enabled.
- A connection error usually means the PostgreSQL host is unreachable or the
  configured connection settings are incorrect.
- HTTP 401 from `collector.php` means the NAS and PHP `PUBLISH_TOKEN` values do
  not match. HTTP 500 is logged server-side and can indicate missing table
  permissions or a database connection problem.
- If no tyre sizes are shown, confirm the collector has populated
  `products` and `price_observations` in the configured database and schema.
- If stars do not save, check that PHP sessions are enabled and that the
  browser accepts the site's session cookie.
