#!/usr/bin/env python3
"""Local dashboard for motorcycle tyre price history."""

import csv
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
from urllib.parse import quote

import pandas as pd
import plotly.express as px
import streamlit as st


ROOT = Path(__file__).resolve().parent
DATABASE = Path(os.environ.get("TYRE_PRICE_DB", ROOT / "tyre_prices.sqlite3")).expanduser()

st.set_page_config(
    page_title="Motorcycle tyre price watch",
    page_icon="🏍️",
    layout="wide",
)


def open_database():
    if not DATABASE.is_file():
        return None
    uri = f"file:{quote(str(DATABASE.resolve()), safe='/')}?mode=ro"
    return sqlite3.connect(uri, uri=True)


def load_common_sizes():
    with (ROOT / "common_motorcycle_tyre_sizes.csv").open(
        newline="", encoding="utf-8"
    ) as file:
        return {
            row["size"]: row["position"]
            for row in csv.DictReader(file)
        }


def available_sizes(connection):
    return pd.read_sql_query(
        """
        SELECT DISTINCT p.size
        FROM products AS p
        JOIN price_observations AS o USING (product_id)
        ORDER BY p.size
        """,
        connection,
    )["size"].tolist()


def load_size_history(connection, size):
    return pd.read_sql_query(
        """
        SELECT p.product_id, p.name, p.size, p.url, o.observed_on, o.price_eur
        FROM products AS p
        JOIN price_observations AS o USING (product_id)
        WHERE p.size = ?
        ORDER BY o.observed_on, p.name
        """,
        connection,
        params=(size,),
        parse_dates=["observed_on"],
    )


st.title("🏍️ Motorcycle tyre price watch")
st.caption("Daily price history collected from Bandenleader.be")

try:
    common_sizes = load_common_sizes()
except (OSError, KeyError, csv.Error) as error:
    st.error(f"Could not read `common_motorcycle_tyre_sizes.csv`: {error}")
    st.stop()

with st.sidebar:
    st.header("Collect prices")
    sizes_to_collect = st.multiselect(
        "Sizes to collect",
        options=list(common_sizes),
        default=["90/90-21", "140/80-18", "150/70-18"],
        max_selections=4,
        format_func=lambda size: f"{size} ({common_sizes[size]})",
        help="Choose up to four common tyre sizes to scrape now.",
    )
    run_collection = st.button(
        "Run price collection",
        type="primary",
        disabled=not sizes_to_collect,
        width="stretch",
    )

if run_collection:
    command = [
        sys.executable,
        str(ROOT / "scrape_prices.py"),
        "--sizes",
        *sizes_to_collect,
        "--database",
        str(DATABASE.resolve()),
    ]
    try:
        with st.spinner("Collecting prices. This may take a few minutes..."):
            result = subprocess.run(
                command,
                cwd=ROOT,
                capture_output=True,
                text=True,
                timeout=300,
                check=False,
            )
    except subprocess.TimeoutExpired:
        st.error(
            "Price collection timed out after five minutes. "
            "No automatic retry was made."
        )
    except OSError as error:
        st.error(f"Could not start the price collection script: {error}")
    else:
        if result.returncode == 0:
            st.success("Price collection completed.")
        else:
            st.error(
                f"Price collection failed with exit code {result.returncode}. "
                "Check the output below."
            )
        if result.stdout.strip():
            st.code(result.stdout.strip())
        if result.stderr.strip():
            st.code(result.stderr.strip(), language="text")

try:
    connection = open_database()
except sqlite3.Error as error:
    st.error(f"Could not open the price database: {error}")
    st.stop()

if connection is None:
    st.info(
        f"No database found at `{DATABASE}` yet. Select sizes under **Collect prices** "
        "and run a collection to create it."
    )
    st.stop()

try:
    sizes = available_sizes(connection)
    if not sizes:
        st.info("The database has no recorded prices yet. Run a price collection to populate it.")
        st.stop()

    with st.sidebar:
        st.divider()
        st.header("Price history")
        selected_size = st.selectbox("Tyre size", sizes)
        period = st.selectbox(
            "History period",
            ["All history", "Last 90 days", "Last 30 days"],
        )
        st.caption(f"Database: `{DATABASE}`")

    history = load_size_history(connection, selected_size)
except (sqlite3.Error, pd.errors.DatabaseError) as error:
    st.error(
        "Could not read the price database. Check that it was created by "
        f"`scrape_prices.py`.\n\n{error}"
    )
    st.stop()
finally:
    connection.close()

if history.empty:
    st.info(f"No price observations found for {selected_size}.")
    st.stop()

history["price_eur"] = pd.to_numeric(history["price_eur"], errors="coerce")
history = history.dropna(subset=["price_eur", "observed_on"])
history["observed_on"] = pd.to_datetime(history["observed_on"])
if history.empty:
    st.warning(f"No valid price observations found for {selected_size}.")
    st.stop()

latest = (
    history.sort_values("observed_on")
    .drop_duplicates("product_id", keep="last")
    .sort_values("price_eur")
)
last_collection = history["observed_on"].max().date()
cheapest = latest.iloc[0]
median_price = latest["price_eur"].median()

metric_columns = st.columns(3)
metric_columns[0].metric("Tyres with recorded prices", f"{len(latest):,}")
metric_columns[1].metric(
    "Lowest latest price",
    f"€{cheapest['price_eur']:.2f}",
    help=f"{cheapest['name']} — last observed {cheapest['observed_on'].date()}",
)
metric_columns[2].metric("Median latest price", f"€{median_price:.2f}")
st.caption(
    f"Latest collection date for {selected_size}: {last_collection}. "
    "Each listed price is the most recently recorded price for that tyre; "
    "the date column shows when it was last seen."
)

st.subheader("Latest recorded prices")
table = latest[["name", "size", "price_eur", "observed_on", "url"]].rename(
    columns={
        "name": "Tyre",
        "size": "Size",
        "price_eur": "Price (€)",
        "observed_on": "Last seen",
        "url": "Product page",
    }
)
st.dataframe(
    table,
    hide_index=True,
    width="stretch",
    column_config={
        "Price (€)": st.column_config.NumberColumn(format="€%.2f"),
        "Last seen": st.column_config.DateColumn(format="YYYY-MM-DD"),
        "Product page": st.column_config.LinkColumn(display_text="Open"),
    },
)

st.subheader("Price history")
product_stats = (
    history.groupby(["product_id", "name", "size"], as_index=False)
    .agg(observations=("observed_on", "nunique"))
    .sort_values(["observations", "name"], ascending=[False, True])
)
product_labels = {
    row.product_id: f"{row.name} — {row.size} (#{row.product_id})"
    for row in product_stats.itertuples()
}
default_products = product_stats["product_id"].head(5).tolist()
selected_products = st.multiselect(
    "Compare tyres",
    options=product_stats["product_id"].tolist(),
    default=default_products,
    format_func=lambda product_id: product_labels[product_id],
    help="Choose tyres to compare. Defaults to the five with the most recorded observations.",
)

if period != "All history":
    days = 90 if period == "Last 90 days" else 30
    cutoff = history["observed_on"].max() - pd.Timedelta(days=days)
    chart_history = history[history["observed_on"] >= cutoff]
else:
    chart_history = history

chart_history = chart_history[chart_history["product_id"].isin(selected_products)]
if chart_history.empty:
    st.info("Select at least one tyre with observations in the chosen period to show its price history.")
else:
    chart_history = chart_history.assign(
        tyre=chart_history["product_id"].map(product_labels)
    )
    figure = px.line(
        chart_history,
        x="observed_on",
        y="price_eur",
        color="tyre",
        markers=True,
        labels={
            "observed_on": "Date",
            "price_eur": "Price (€)",
            "tyre": "Tyre",
        },
        hover_data={"product_id": False, "size": True, "url": False},
    )
    figure.update_layout(
        hovermode="x unified",
        legend_title_text="Tyre",
        yaxis_tickprefix="€",
        yaxis_tickformat=".2f",
        margin=dict(l=12, r=12, t=24, b=12),
    )
    st.plotly_chart(figure, width="stretch")

st.caption(
    "A missing observation means the tyre was not present in that day's collected results; "
    "it does not necessarily mean the tyre was out of stock."
)
