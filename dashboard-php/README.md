# PHP dashboard

This folder contains a PHP alternative to the Streamlit dashboard. The original
Python dashboard remains unchanged. This version reads the same SQLite database,
shows the latest prices and collection summary, supports favorite tyres, and
draws the selected favorites' price history. The database is opened in
SQLite query-only mode; price collection remains a separate Python task.

## Requirements

- Synology Web Station with a PHP profile enabled.
- PHP 7.4 or newer, with `PDO_SQLITE` enabled.
- The project files from the repository root, including
  `common_motorcycle_tyre_sizes.csv`, `scrape_prices.py`, `tyre_sizes.py`, and
  `tyre_prices.sqlite3` if you already have a database.
- The web server account must be able to read the project and database files.
  The `dashboard-php` directory must also be writable so favorite selections
  can be saved to `favorites.json`.

## Install on Synology

1. Copy the `dashboard-php` folder into your project directory. Keep the root
   CSV file beside it, and keep the SQLite database where it already is. For
   example:

   ```text
   /volume1/DATA/scripts/bandjes/
   ├── common_motorcycle_tyre_sizes.csv
   ├── tyre_prices.sqlite3
   └── dashboard-php/
       ├── index.php
       └── style.css
   ```

2. In DSM Package Center, install Web Station and a PHP package available for
   your NAS. Configure a PHP profile with the **PDO_SQLITE** extension enabled.
   The exact PHP package and Web Station steps vary by DSM version and NAS
   model.

3. In Web Station, create a portal or virtual host whose document root is the
   `dashboard-php` folder and whose PHP profile is the one with PDO SQLite.
   Alternatively, configure an existing web server to use this folder as its
   document root. Open the portal URL in a browser to verify that the page
   loads.

4. Ensure the web-server account can read the database and CSV. If your SQLite
   file is stored somewhere else, configure the `TYRE_PRICE_DB` environment
   variable for the PHP service with its absolute path. The default database
   location is the repository root, one directory above `dashboard-php`.

5. Grant the web-server account write access to `dashboard-php` to persist
   favorites. This is separate from the SQLite database, which remains
   read-only to this app.

The database can remain in the shared project folder. Continue using the
repository's Python collector on a DSM scheduled task to add observations; the
PHP dashboard does not start the collector.

## Security

The dashboard has no user login. Keep the Web Station portal on a trusted LAN
or access it through a VPN; do not expose it directly to the internet. Use DSM
permissions to limit who can reach the portal and who can modify its files.

## Troubleshooting

- A message that PDO SQLite is unavailable means the selected Web Station PHP
  profile needs its `PDO_SQLITE` extension enabled.
- A database read error usually means the PHP service account cannot read the
  database, or `TYRE_PRICE_DB` points at the wrong file.
- If stars do not save, grant the PHP service account write permission to this
  folder and check that PHP sessions are enabled.
- Collection does not run from this page. Use the existing Python collector
  and DSM Task Scheduler instructions in the repository's main README.
