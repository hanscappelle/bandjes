# Pricewatch for Motorcycle Tyres

Scripts and Dashboard to collect tyres prices over time for a specific set of tyre sizes. Using bandenleader.be webshop as a, currently single, source. 

Check for example: https://www.bandenleader.be/motor-band-150-70-18/
Optionally add ?p=2 and more for next pages, script will automatically traverse pagination when found. 

# Warning !!

Bandenleader is blocking after too many requests, still looking into this.

## Daily price collection

The standard-library Python script collects prices for one or more sizes from
`common_motorcycle_tyre_sizes.csv` and stores daily observations in
`tyre_prices.sqlite3`. It follows pagination until no next page is available,
waits ten seconds between pages and sizes by default, and stops without retrying
if the site returns a blocking or rate-limit response.

There is no fixed size limit per run. Larger collection requests require more
page requests and increase the risk of the site blocking further requests.

```sh
python3 scrape_prices.py --sizes 90/90R21 140/80R18 150/70R18
```

Run the command once per day to build the price history. The script discovers
the number of pages by following each page's next-page link and prints the
number processed per size. Use `--database PATH` to choose a different
database, `--delay SECONDS` to change the wait between requests, or
`--max-pages 1` to intentionally stop after the first page. Size notation is normalized to
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

## Schedule daily collection on Synology DSM

The scheduled collector is a command-line script; the dashboard does not need
to be running. Install **Python 3** from DSM Package Center, then create a
persistent folder on a shared volume, for example `/volume1/pricewatch`. Copy
these three files into it:

- `scrape_prices.py`
- `tyre_sizes.py`
- `common_motorcycle_tyre_sizes.csv`

The account used by the scheduled task needs permission to read these files and
write to the folder. Connect over SSH and check Python's path and version:

```sh
command -v python3
python3 --version
```

Use the full path printed by `command -v` in the task, since DSM scheduled tasks
may not have the same `PATH` as an interactive shell. Test one small collection
over SSH first, replacing `/usr/local/bin/python3` if needed:

```sh
cd /volume1/pricewatch
/usr/local/bin/python3 scrape_prices.py --sizes 90/90R21 --delay 10 --database /volume1/pricewatch/tyre_prices.sqlite3
```

If that succeeds, open **Control Panel > Task Scheduler**, create a **Scheduled
Task > User-defined script**, choose the account that has access to the folder,
and set the schedule to run daily at a time when the NAS is on and connected to
the internet. Use this script (again, update the Python path if needed):

```sh
cd /volume1/pricewatch
/usr/local/bin/python3 scrape_prices.py --sizes 90/90R21 --delay 10 --database /volume1/pricewatch/tyre_prices.sqlite3 >> /volume1/pricewatch/collection.log 2>&1
```

Add the sizes you want after `--sizes`; there is no fixed size limit. Each
selected size is fully paginated by default. Larger requests mean more page
requests and increase the risk of Bandenleader blocking the NAS. Start with a
small request and increase the delay if needed; a longer delay does not
guarantee the site will allow the requests. The script does not automatically
retry when blocked. Check `collection.log` in File Station or over SSH to see
the result of each scheduled run. The SQLite database is persistent at the
specified path; include it in your regular NAS backup.

This command uses the NAS's direct network connection. The Firefox VPN profile
option requires a graphical Firefox session and is not suitable for a normal
headless scheduled task. If you need a VPN, configure a supported VPN connection
for the NAS/network itself and verify it before scheduling.

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

The **Database path** field in the sidebar controls which SQLite database is
read and collected into; the last entered path is remembered locally between
dashboard runs. If none has been saved, it defaults to `tyre_prices.sqlite3`
next to the script, unless `TYRE_PRICE_DB` sets a different initial default. It shows each
selected size's latest recorded prices, last-seen date and time, summary figures, and
selectable per-tyre price trends. Use **Collect prices** in the sidebar to
choose any number of sizes and manually run the scraper. Larger requests
increase the risk of the source blocking further requests. The **Firefox VPN
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
The **Sizes to collect** selection is remembered locally between dashboard runs
in `collection_sizes.json` under the TyrePriceWatch application settings folder.

Use the **Favorite** checkbox in the **Latest recorded prices** table to quickly
save or remove a model for the selected size. Favorites are saved immediately
and are the only tyres included in the price-history chart. Favorite selections
are stored locally in `favorites.json` under the TyrePriceWatch application
settings folder, separately from the price database.

On macOS, Firefox profiles are usually stored under
`~/Library/Application Support/Firefox/Profiles/`. Enter the profile folder
manually, or set `FIREFOX_PROFILE` to prefill the field. Check **Remember
profile location on this device** to save that path locally for the next run;
uncheck it before collecting to forget the saved path. The path is stored in
`~/Library/Application Support/TyrePriceWatch/firefox_profile.txt` on macOS
with user-only file permissions.
