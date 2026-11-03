# pricewatch for motorcycle tyres

Using bandenleader.be as a source

Check for example:
https://www.bandenleader.be/motor-band-150-70-18/
Optionally add ?p=2 and more for next pages
the script will automatically traverse pagination

# Warning !!

Bandenleader is blocking after too many requests, still looking into this.

## Daily price collection

The standard-library Python script collects prices for one to four sizes from
`common_motorcycle_tyre_sizes.csv` and stores daily observations in
`tyre_prices.sqlite3`. It follows pagination until no next page is available,
waits five seconds between pages and sizes, and stops without retrying if the
site returns a blocking or rate-limit response.

```sh
python3 scrape_prices.py --sizes 90/90R21 140/80R18 150/70R18
```

Run the command once per day to build the price history. The script discovers
the number of pages by following each page's next-page link and prints the
number processed per size. Use `--database PATH` to choose a different
database, `--delay 10` to wait longer between requests, or `--max-pages 1` to
intentionally stop after the first page. Size notation is normalized to
`150/70R17`, combining equivalent source formats such as `150/70-17`,
`150/70 R17`, and `150/70 ZR17` in the dashboard. Each observation is keyed by
its local collection timestamp and Bandenleader product ID. Existing date-only
observations are migrated to midnight when the scraper next runs; subsequent
runs on the same day are stored separately.

To collect through Firefox instead of Python's direct network connection, pass
a dedicated Firefox profile:

```sh
python3 scrape_prices.py --firefox-profile "/path/to/firefox/profile" --sizes 90/90R21 140/80R18 150/70R18
```

Install Firefox and configure a separate profile with the VPN extension
installed and signed in. If the extension supports it, enable automatic VPN
connection when Firefox starts. Selenium launches a separate Firefox session
using a copy of that profile; it does not attach to your open Firefox window.
The VPN extension may not connect automatically in the new session. In the
dashboard, use **Wait before scraping** to give yourself time to switch to the
launched Firefox window, connect the VPN extension, and then let collection
continue. The default wait is 60 seconds. From the command line, use
`--vpn-wait 60` for the same pause.
On macOS, the first launch may be blocked by Gatekeeper or a privacy/security
permission prompt. Approve Firefox in the macOS dialog/System Settings, then
retry the collection. If macOS reports Firefox is damaged or cannot verify it,
install or open Firefox from the official Mozilla download and approve it in
System Settings > Privacy & Security. Do not bypass these protections for an
untrusted Firefox download.

Example query for a product's price history:

```sql
SELECT p.name, p.size, o.observed_at, o.price_eur
FROM price_observations AS o
JOIN products AS p USING (product_id)
WHERE p.product_id = '1705415'
ORDER BY o.observed_at;
```

## Local dashboard

Install the dashboard dependencies and start the app:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/streamlit run dashboard.py
```

The dashboard reads `tyre_prices.sqlite3` next to the script. It shows each
selected size's latest recorded prices, last-seen date and time, summary figures, and
selectable per-tyre price trends. Use **Collect prices** in the sidebar to
choose up to four sizes and manually run the scraper. The default **Firefox VPN
profile** mode launches a separate automated Firefox session using a copy of
the selected profile; it does not control your existing Firefox window. The
profile should be dedicated to scraping, with the VPN extension installed,
configured, signed in, and set to connect automatically if the extension
supports it. To find its folder, open `about:profiles` in Firefox and copy the
profile's **Root Directory** path. Select **Direct HTTP** to use Python's
network connection instead. The scraper never silently switches between modes
and stops without retrying when the source blocks a request. Both modes save
to the dashboard's database. Set `FIREFOX_PROFILE` to prefill the profile path
and `TYRE_PRICE_DB` to use a database at a different path.

Use the **Favorite** checkbox in the **Latest recorded prices** table to quickly
save or remove a model for the selected size. Favorites are saved immediately
and automatically selected in **Compare tyres** and included in the
price-history chart. The comparison also starts with the five most-observed
other tyres; adjust the selection as needed. Favorite selections are stored locally in
`favorites.json` under the TyrePriceWatch application settings folder,
separately from the price database.

On macOS, Firefox profiles are usually stored under
`~/Library/Application Support/Firefox/Profiles/`. Enter the profile folder
manually, or set `FIREFOX_PROFILE` to prefill the field. Check **Remember
profile location on this device** to save that path locally for the next run;
uncheck it before collecting to forget the saved path. The path is stored in
`~/Library/Application Support/TyrePriceWatch/firefox_profile.txt` on macOS
with user-only file permissions.
