#!/usr/bin/env python3
"""Local dashboard for motorcycle tyre price history."""

import csv
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
from urllib.parse import quote

import pandas as pd
import plotly.express as px
import streamlit as st

from tyre_sizes import normalize_size


ROOT = Path(__file__).resolve().parent
DATABASE = Path(os.environ.get("TYRE_PRICE_DB", ROOT / "tyre_prices.sqlite3")).expanduser()

st.set_page_config(
    page_title="Motorcycle Tyre Price Watch",
    page_icon="🏍️",
    layout="wide",
)


def open_database():
    if not DATABASE.is_file():
        return None
    uri = f"file:{quote(str(DATABASE.resolve()), safe='/')}?mode=ro"
    return sqlite3.connect(uri, uri=True)


def firefox_profile_settings_path():
    if sys.platform == "darwin":
        settings_dir = Path.home() / "Library/Application Support/TyrePriceWatch"
    else:
        settings_dir = Path(
            os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
        ) / "tyre-price-watch"
    return settings_dir / "firefox_profile.txt"


def load_remembered_firefox_profile():
    settings_path = firefox_profile_settings_path()
    if not settings_path.is_file():
        return ""
    return settings_path.read_text(encoding="utf-8").strip()


def remember_firefox_profile(profile_path):
    settings_path = firefox_profile_settings_path()
    settings_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    settings_path.write_text(profile_path.strip(), encoding="utf-8")
    settings_path.chmod(0o600)


def forget_firefox_profile():
    settings_path = firefox_profile_settings_path()
    if settings_path.exists():
        settings_path.unlink()


def favorites_settings_path():
    if sys.platform == "darwin":
        settings_dir = Path.home() / "Library/Application Support/TyrePriceWatch"
    else:
        settings_dir = Path(
            os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
        ) / "tyre-price-watch"
    return settings_dir / "favorites.json"


def collection_sizes_settings_path():
    if sys.platform == "darwin":
        settings_dir = Path.home() / "Library/Application Support/TyrePriceWatch"
    else:
        settings_dir = Path(
            os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
        ) / "tyre-price-watch"
    return settings_dir / "collection_sizes.json"


def load_collection_sizes():
    settings_path = collection_sizes_settings_path()
    if not settings_path.is_file():
        return None
    with settings_path.open(encoding="utf-8") as file:
        sizes = json.load(file)
    if not isinstance(sizes, list) or not all(
        isinstance(size, str) for size in sizes
    ):
        raise ValueError(f"Invalid collection sizes format in {settings_path}")
    return sizes


def save_collection_sizes(sizes):
    settings_path = collection_sizes_settings_path()
    settings_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary_path = settings_path.with_suffix(".json.tmp")
    try:
        with temporary_path.open("w", encoding="utf-8") as file:
            json.dump(sizes, file, indent=2)
            file.write("\n")
        temporary_path.chmod(0o600)
        temporary_path.replace(settings_path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()


def load_favorites():
    settings_path = favorites_settings_path()
    if not settings_path.is_file():
        return {}
    with settings_path.open(encoding="utf-8") as file:
        favorites = json.load(file)
    if not isinstance(favorites, dict) or not all(
        isinstance(size, str)
        and isinstance(product_ids, list)
        and all(isinstance(product_id, str) for product_id in product_ids)
        for size, product_ids in favorites.items()
    ):
        raise ValueError(f"Invalid favorites format in {settings_path}")
    return favorites


def save_favorites(favorites):
    settings_path = favorites_settings_path()
    settings_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary_path = settings_path.with_suffix(".json.tmp")
    try:
        with temporary_path.open("w", encoding="utf-8") as file:
            json.dump(favorites, file, indent=2, sort_keys=True)
            file.write("\n")
        temporary_path.chmod(0o600)
        temporary_path.replace(settings_path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()


def load_common_sizes():
    with (ROOT / "common_motorcycle_tyre_sizes.csv").open(
        newline="", encoding="utf-8"
    ) as file:
        return {
            normalize_size(row["size"]): row["position"]
            for row in csv.DictReader(file)
        }


def available_sizes(connection):
    raw_sizes = pd.read_sql_query(
        """
        SELECT DISTINCT p.size
        FROM products AS p
        JOIN price_observations AS o USING (product_id)
        ORDER BY p.size
        """,
        connection,
    )["size"].tolist()
    return sorted({normalize_size(size) for size in raw_sizes})


def observation_timestamp_column(connection):
    columns = {
        row[1]
        for row in connection.execute("PRAGMA table_info(price_observations)")
    }
    if "observed_at" in columns:
        return "observed_at"
    if "observed_on" in columns:
        return "observed_on"
    raise sqlite3.DatabaseError(
        "price_observations has neither observed_at nor observed_on."
    )


def load_size_history(connection, size):
    timestamp_column = observation_timestamp_column(connection)
    history = pd.read_sql_query(
        f"""
        SELECT p.product_id, p.name, p.size, p.url,
               o.{timestamp_column} AS observed_at, o.price_eur
        FROM products AS p
        JOIN price_observations AS o USING (product_id)
        ORDER BY o.{timestamp_column}, p.name
        """,
        connection,
        parse_dates=["observed_at"],
    )
    history["size"] = history["size"].map(normalize_size)
    return history[history["size"] == size]


st.title("🏍️ Motorcycle Tyre Price Watch")
st.caption("Daily price history collected from Bandenleader.be")

try:
    common_sizes = load_common_sizes()
except (OSError, KeyError, csv.Error) as error:
    st.error(f"Could not read `common_motorcycle_tyre_sizes.csv`: {error}")
    st.stop()

try:
    remembered_firefox_profile = load_remembered_firefox_profile()
except (OSError, UnicodeError) as error:
    remembered_firefox_profile = ""
    st.error(f"Could not read the remembered Firefox profile setting: {error}")

try:
    favorites_by_size = load_favorites()
except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
    favorites_by_size = {}
    st.error(f"Could not read saved tyre favorites: {error}")

try:
    remembered_collection_sizes = load_collection_sizes()
except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
    remembered_collection_sizes = None
    st.error(f"Could not read saved collection sizes: {error}")

with st.sidebar:
    st.header("Collect Prices")
    connection_mode = st.radio(
        "Connection",
        ["Firefox VPN profile", "Direct HTTP"],
        help=(
            "Firefox mode launches a separate automated Firefox session using a copy "
            "of your configured profile."
        ),
    )
    firefox_profile = ""
    vpn_wait = 0
    if connection_mode == "Firefox VPN profile":
        firefox_profile = st.text_input(
            "Firefox profile folder",
            value=os.environ.get("FIREFOX_PROFILE", remembered_firefox_profile),
            key="firefox_profile_path",
            placeholder="~/Library/Application Support/Firefox/Profiles/...",
            help=(
                "Use a dedicated Firefox profile with your VPN extension installed, "
                "configured, and connected."
            ),
        ).strip()
        remember_profile = st.checkbox(
            "Remember profile location on this device",
            value=bool(remembered_firefox_profile),
            help="Stores only the profile folder path locally for the next dashboard run.",
        )
        st.caption(
            "Firefox will launch separately; this does not control your existing Firefox window."
        )
        vpn_wait = st.number_input(
            "Wait before scraping (seconds)",
            min_value=0,
            max_value=300,
            value=60,
            step=15,
            help=(
                "Time after Firefox opens to manually connect the VPN extension "
                "before the first price request."
            ),
        )

    default_collection_sizes = (
        ["90/90R21", "140/80R18", "150/70R18"]
        if remembered_collection_sizes is None
        else [
            size
            for size in remembered_collection_sizes
            if size in common_sizes
        ]
    )
    sizes_to_collect = st.multiselect(
        "Sizes to collect",
        options=list(common_sizes),
        default=default_collection_sizes,
        key="sizes_to_collect",
        format_func=lambda size: f"{size} ({common_sizes[size]})",
        help="Choose any number of common tyre sizes to scrape now.",
    )
    try:
        save_collection_sizes(sizes_to_collect)
    except OSError as error:
        st.error(f"Could not save collection sizes: {error}")
    if len(sizes_to_collect) > 5:
        st.warning(
            "Larger collection requests make more page requests and increase "
            "the risk of the source blocking further requests."
        )
    run_collection = st.button(
        "Run price collection",
        type="primary",
        disabled=(
            not sizes_to_collect
            or (connection_mode == "Firefox VPN profile" and not firefox_profile)
        ),
        width="stretch",
    )

if run_collection:
    if connection_mode == "Firefox VPN profile":
        try:
            if remember_profile:
                remember_firefox_profile(firefox_profile)
            else:
                forget_firefox_profile()
        except OSError as error:
            st.error(f"Could not update the remembered Firefox profile setting: {error}")

    command = [
        sys.executable,
        str(ROOT / "scrape_prices.py"),
        "--sizes",
        *sizes_to_collect,
        "--database",
        str(DATABASE.resolve()),
    ]
    if connection_mode == "Firefox VPN profile":
        command.extend(
            [
                "--firefox-profile",
                firefox_profile,
                "--vpn-wait",
                str(int(vpn_wait)),
            ]
        )
    try:
        with st.spinner(
            (
                f"Firefox is open. Connect the VPN; scraping starts in "
                f"{int(vpn_wait)} seconds..."
                if connection_mode == "Firefox VPN profile" and vpn_wait
                else "Collecting prices in Firefox. This may take a few minutes..."
            )
            if connection_mode == "Firefox VPN profile"
            else "Collecting prices. This may take a few minutes..."
        ):
            result = subprocess.run(
                command,
                cwd=ROOT,
                capture_output=True,
                text=True,
                timeout=1800 + int(vpn_wait),
                check=False,
            )
    except subprocess.TimeoutExpired:
        st.error(
            "Price collection timed out after 30 minutes. "
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
        st.header("Price History")
        selected_size = st.selectbox("Tyre size", sizes)
        period = st.selectbox(
            "History period",
            ["All history", "Last 90 days", "Last 30 days"],
        )
        st.caption(f"Database: `{DATABASE}`")

    history = load_size_history(connection, selected_size)
except (sqlite3.Error, pd.errors.DatabaseError, ValueError) as error:
    st.error(
        "Could not read the price database. Check that it was created by "
        f"`scrape_prices.py`.\n\n{error}"
    )
    st.stop()
finally:
    connection.close()

st.header(f"Selected Tyre Size: {selected_size}")

if history.empty:
    st.info(f"No price observations found for {selected_size}.")
    st.stop()

history["price_eur"] = pd.to_numeric(history["price_eur"], errors="coerce")
history = history.dropna(subset=["price_eur", "observed_at"])
history["observed_at"] = pd.to_datetime(history["observed_at"])
if history.empty:
    st.warning(f"No valid price observations found for {selected_size}.")
    st.stop()

latest = (
    history.sort_values("observed_at")
    .drop_duplicates("product_id", keep="last")
    .sort_values("price_eur")
)
product_stats = (
    history.groupby(["product_id", "name", "size"], as_index=False)
    .agg(observations=("observed_at", "nunique"))
    .sort_values(["observations", "name"], ascending=[False, True])
)
product_labels = {
    row.product_id: f"{row.name} — {row.size} (#{row.product_id})"
    for row in product_stats.itertuples()
}
available_product_ids = product_stats["product_id"].tolist()
saved_favorites = [
    product_id
    for product_id in favorites_by_size.get(selected_size, [])
    if product_id in available_product_ids
]
last_collection = history["observed_at"].max().strftime("%Y-%m-%d %H:%M:%S")
cheapest = latest.iloc[0]
median_price = latest["price_eur"].median()

metric_columns = st.columns(3)
metric_columns[0].metric("Tyres with recorded prices", f"{len(latest):,}")
metric_columns[1].metric(
    "Lowest latest price",
    f"€{cheapest['price_eur']:.2f}",
    help=(
        f"{cheapest['name']} — last observed "
        f"{cheapest['observed_at'].strftime('%Y-%m-%d %H:%M:%S')}"
    ),
)
metric_columns[2].metric("Median latest price", f"€{median_price:.2f}")
st.caption(
    f"Latest collection for {selected_size}: {last_collection}. "
    "Each listed price is the most recently recorded price for that tyre; "
    "the timestamp column shows when it was last seen."
)

st.subheader("Latest Recorded Prices")
st.caption("Toggle Favorite in the table to save or remove a tyre from your favorites.")
table = latest[
    ["product_id", "name", "size", "price_eur", "observed_at", "url"]
].rename(
    columns={
        "product_id": "Product ID",
        "name": "Tyre",
        "size": "Size",
        "price_eur": "Price (€)",
        "observed_at": "Last seen",
        "url": "Product page",
    }
).set_index("Product ID")
table["Favorite"] = table.index.isin(saved_favorites)
edited_table = st.data_editor(
    table,
    hide_index=True,
    width="stretch",
    disabled=["Tyre", "Size", "Price (€)", "Last seen", "Product page"],
    column_config={
        "Favorite": st.column_config.CheckboxColumn(
            help="Save this tyre as a favorite for this size."
        ),
        "Price (€)": st.column_config.NumberColumn(format="€%.2f"),
        "Last seen": st.column_config.DatetimeColumn(format="YYYY-MM-DD HH:mm:ss"),
        "Product page": st.column_config.LinkColumn(display_text="Open"),
    },
    key=f"latest_favorites_{selected_size}",
)
table_favorites = edited_table.index[edited_table["Favorite"]].tolist()
favorites_changed = set(table_favorites) != set(saved_favorites)
if favorites_changed:
    updated_favorites = dict(favorites_by_size)
    updated_favorites[selected_size] = table_favorites
    try:
        save_favorites(updated_favorites)
    except OSError as error:
        st.error(f"Could not save tyre favorites: {error}")
    else:
        favorites_by_size = updated_favorites
        saved_favorites = table_favorites

st.subheader("Price History")
comparison_products = saved_favorites

if period != "All history":
    days = 90 if period == "Last 90 days" else 30
    cutoff = history["observed_at"].max() - pd.Timedelta(days=days)
    chart_history = history[history["observed_at"] >= cutoff]
else:
    chart_history = history

chart_history = chart_history[
    chart_history["product_id"].isin(comparison_products)
]
if chart_history.empty:
    st.info("Mark tyres as favorites in the latest recorded prices table to compare their price history.")
else:
    chart_history = chart_history.assign(
        tyre=chart_history["product_id"].map(product_labels)
    )
    figure = px.line(
        chart_history,
        x="observed_at",
        y="price_eur",
        color="tyre",
        markers=True,
        labels={
            "observed_at": "Date and time",
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
