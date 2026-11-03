# pricewatch for motorcycle tyres

Using bandenleader.be as a source

Check for example 
https://www.bandenleader.be/motor-band-150-70-18/?p=2
starting at p=1

## Daily price collection

The standard-library Python script collects prices for one to four sizes from
`common_motorcycle_tyre_sizes.csv` and stores daily observations in
`tyre_prices.sqlite3`. It follows at most two listing pages per size by default,
waits five seconds between requests, and stops without retrying if the site
returns a blocking or rate-limit response.

```sh
python3 scrape_prices.py --sizes 90/90-21 140/80-18 150/70-18
```

Run the command once per day to build the price history. Use `--database PATH`
to choose a different database, or `--max-pages 1` to make fewer requests. Each
observation is keyed by date and Bandenleader's product ID; rerunning on the
same day updates that day's prices rather than adding duplicates.

Example query for a product's price history:

```sql
SELECT p.name, p.size, o.observed_on, o.price_eur
FROM price_observations AS o
JOIN products AS p USING (product_id)
WHERE p.product_id = '1705415'
ORDER BY o.observed_on;
```

## Local dashboard

Install the dashboard dependencies and start the app:

```sh
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r requirements.txt
streamlit run dashboard.py
```

The dashboard reads `tyre_prices.sqlite3` next to the script. It shows each
selected size's latest recorded prices, last-seen dates, summary figures, and
selectable per-tyre price trends. Use **Collect prices** in the sidebar to
choose up to four sizes and manually run the scraper; it uses the dashboard's
database, displays the result, and stops without retrying when the source blocks
the request. Set `TYRE_PRICE_DB` to use a database at a different path.
