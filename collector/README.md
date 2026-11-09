# HTTPS price collector

This collector shares the root `tyre_sizes.py` and
`common_motorcycle_tyre_sizes.csv` files and publishes collected observations
to the PostgreSQL database through the authenticated Alwaysdata HTTPS endpoint.
It does not connect directly to PostgreSQL or require a PostgreSQL Python driver.

## Setup

Deploy
[`dashboard-php-public/collector.php`](../dashboard-php-public/collector.php)
to the Alwaysdata dashboard's document root and configure these environment
variables for PHP:

- `PUBLISH_TOKEN`: a long random secret shared only with the NAS.
- `COLLECTOR_DATABASE_URL`: PostgreSQL URI for a dedicated writer role.

The dashboard's `DATABASE_URL` can remain a separate read-only connection.
Create the tables once using [`schema.sql`](schema.sql) in the PostgreSQL
database, then grant the writer role only the access it needs. For example,
after substituting the actual role name:

```sql
GRANT USAGE ON SCHEMA public TO bandjes_collector;
GRANT SELECT, INSERT, UPDATE ON public.products, public.price_observations
    TO bandjes_collector;
```

On the NAS, store the endpoint and matching token in a private environment file,
for example `secrets/collector-api-config.env`:

```sh
PUBLISH_URL='https://betyre.alwaysdata.net/collector.php'
PUBLISH_TOKEN='your-long-random-secret'
```

Restrict the file to the task account, then source it before running:

```sh
chmod 600 secrets/collector-api-config.env
```

From the project root, source the file and run the collector:

```sh
set -a
. secrets/collector-api-config.env
set +a
python3 collector/scrape_prices.py --sizes 90/90R21 140/80R18
```

The endpoint accepts authenticated HTTPS POST requests, validates the payload,
and writes each batch transactionally. The collector uses Python's standard
library for publishing, so no PostgreSQL Python driver is required. Do not
place the token in the command line, repository, or collection log. For a
scheduled DSM task, load both variables from a private file readable only by
the task account. Generate a token with `openssl rand -hex 32`.

The script uses direct HTTP requests with the same pagination, request delay,
and timeout settings as the SQLite collector. For example, `--delay 10`
controls the delay between page requests and `--max-pages 1` limits pages per
size.
Observations are stored with a local collection timestamp in
`price_observations`; product details are upserted into `products`. Initialize
the database tables once using [`schema.sql`](schema.sql). The endpoint writes
each batch transactionally; the dashboard itself remains read-only.
