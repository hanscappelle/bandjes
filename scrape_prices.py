#!/usr/bin/env python3
"""Collect Bandenleader motorcycle tyre prices into a local SQLite database."""

import argparse
import csv
from datetime import date
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from pathlib import Path
import re
import sqlite3
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen


BASE_URL = "https://www.bandenleader.be/"
SIZE_PATTERN = re.compile(
    r"(?P<width>\d{2,3})\s*/\s*(?P<aspect>\d{2,3})"
    r"\s*(?:-|/|\s)?\s*(?:ZR|VB|R|B)?\s*(?P<rim>\d{2})",
    re.IGNORECASE,
)
VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "param", "source", "track", "wbr",
}


def normalize_size(value):
    match = SIZE_PATTERN.fullmatch(value.strip())
    if not match:
        raise ValueError(f"Invalid tyre size: {value!r} (expected e.g. 90/90-21)")
    return "{width}/{aspect}-{rim}".format(**match.groupdict())


class ListingParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.products = []
        self.next_page = None
        self.card = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("a", "link") and "next" in attrs.get("rel", "").split():
            self.next_page = attrs.get("href") or self.next_page

        classes = attrs.get("class", "").split()
        if tag == "div" and "product-list-item" in classes:
            self.card = {
                "tags": [tag],
                "div_depth": 1,
                "url": "",
                "id": None,
                "texts": [],
                "price_text": [],
                "price_depth": None,
            }
        elif self.card:
            if tag not in VOID_TAGS:
                self.card["tags"].append(tag)
            if tag == "div":
                self.card["div_depth"] += 1
            if tag == "a" and "product-list-item__content" in classes:
                self.card["url"] = attrs.get("href", "")
                self.card["id"] = attrs.get("data-gtm--select-item-identifier-param")
            if "price__amount--value" in classes:
                self.card["price_text"] = []
                self.card["price_depth"] = len(self.card["tags"])

    def handle_endtag(self, tag):
        if not self.card:
            return
        try:
            matching_tag = len(self.card["tags"]) - 1 - self.card["tags"][::-1].index(tag)
        except ValueError:
            return
        del self.card["tags"][matching_tag:]
        if self.card["price_depth"] and len(self.card["tags"]) < self.card["price_depth"]:
            self.card["price_depth"] = None
        if tag == "div":
            self.card["div_depth"] -= 1
        if self.card["div_depth"] == 0:
            self._finish_card()

    def handle_data(self, data):
        if not self.card or not data.strip():
            return
        text = " ".join(data.split())
        self.card["texts"].append(text)
        if self.card["price_depth"] is not None:
            self.card["price_text"].append(text)

    def _finish_card(self):
        card = self.card
        self.card = None
        combined = " ".join(card["texts"])
        size_match = SIZE_PATTERN.search(combined)
        if not size_match or not card["price_text"]:
            return

        size = size_match.group(0).strip()
        name_text = next(
            (text for text in card["texts"] if not SIZE_PATTERN.search(text)),
            "",
        )
        price_text = " ".join(card["price_text"])
        try:
            price = Decimal(price_text.replace(".", "").replace(",", "."))
        except InvalidOperation:
            return

        self.products.append(
            {
                "id": card["id"],
                "name": name_text.strip(),
                "size": size,
                "price": price,
                "url": card["url"],
            }
        )


def load_common_sizes(path):
    with path.open(newline="", encoding="utf-8") as file:
        return {normalize_size(row["size"]) for row in csv.DictReader(file)}


def fetch_listing(url, timeout):
    request = Request(
        url,
        headers={"User-Agent": "TyrePriceTracker/1.0 (personal daily price tracking)"},
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", errors="replace")
    except HTTPError as error:
        if error.code == 429:
            raise RuntimeError(
                "Bandenleader returned HTTP 429; stopping without retrying."
            ) from error
        if error.code in (403, 503):
            raise RuntimeError(
                f"Bandenleader returned HTTP {error.code}; stopping without retrying."
            ) from error
        raise RuntimeError(f"Bandenleader returned HTTP {error.code} for {url}") from error
    except URLError as error:
        raise RuntimeError(f"Could not fetch {url}: {error.reason}") from error


def scrape_size(size, max_pages, delay, timeout):
    width, remainder = size.split("/")
    aspect, rim = remainder.split("-")
    url = f"{BASE_URL}motor-band-{width}-{aspect}-{rim}/"
    seen_urls = set()
    products = []
    truncated = False

    for page_number in range(max_pages):
        if url in seen_urls:
            raise RuntimeError(f"Repeated pagination URL while scraping {size}: {url}")
        seen_urls.add(url)

        parser = ListingParser()
        parser.feed(fetch_listing(url, timeout))
        page_products = [
            product
            for product in parser.products
            if normalize_size(product["size"]) == size
        ]
        for product in page_products:
            product["url"] = quote(
                urljoin(BASE_URL, product["url"]),
                safe=":/?&=%#",
            )
        products.extend(page_products)

        next_page = urljoin(url, parser.next_page) if parser.next_page else None
        if not next_page:
            break
        if page_number + 1 == max_pages:
            truncated = True
            break
        if delay:
            time.sleep(delay)
        url = quote(next_page, safe=":/?&=%#")

    if not products:
        raise RuntimeError(
            f"No products were parsed for {size}; the page layout may have changed."
        )
    for product in products:
        if not product["id"]:
            raise RuntimeError(f"Product is missing its stable listing ID: {product['url']}")
    return products, truncated


def initialize_database(connection):
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS products (
            product_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            size TEXT NOT NULL,
            url TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS price_observations (
            observed_on TEXT NOT NULL,
            product_id TEXT NOT NULL REFERENCES products(product_id),
            price_eur NUMERIC NOT NULL,
            PRIMARY KEY (observed_on, product_id)
        );
        CREATE INDEX IF NOT EXISTS price_observations_by_product
            ON price_observations(product_id, observed_on);
        """
    )


def save_observations(connection, observed_on, products):
    with connection:
        for product in products:
            connection.execute(
                """
                INSERT INTO products (product_id, name, size, url)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(product_id) DO UPDATE SET
                    name = excluded.name, size = excluded.size, url = excluded.url
                """,
                (product["id"], product["name"], product["size"], product["url"]),
            )
            connection.execute(
                """
                INSERT INTO price_observations (observed_on, product_id, price_eur)
                VALUES (?, ?, ?)
                ON CONFLICT(observed_on, product_id)
                DO UPDATE SET price_eur = excluded.price_eur
                """,
                (observed_on, product["id"], str(product["price"])),
            )


def main():
    root = Path(__file__).resolve().parent
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument(
        "--sizes",
        nargs="+",
        required=True,
        metavar="SIZE",
        help="1-4 sizes from common_motorcycle_tyre_sizes.csv (e.g. 90/90-21)",
    )
    argument_parser.add_argument(
        "--database",
        type=Path,
        default=root / "tyre_prices.sqlite3",
        help="SQLite database path (default: tyre_prices.sqlite3 next to this script)",
    )
    argument_parser.add_argument(
        "--delay",
        type=float,
        default=5,
        help="seconds between page requests (default: 5; set 0 to disable)",
    )
    argument_parser.add_argument(
        "--max-pages",
        type=int,
        default=2,
        help="maximum listing pages per size (default: 2; maximum: 10)",
    )
    argument_parser.add_argument(
        "--timeout",
        type=float,
        default=20,
        help="request timeout in seconds (default: 20)",
    )
    args = argument_parser.parse_args()

    try:
        if not 1 <= len(args.sizes) <= 4:
            raise ValueError("Select between one and four sizes per run.")
        if not 1 <= args.max_pages <= 10:
            raise ValueError("--max-pages must be between 1 and 10.")
        if args.delay < 0 or args.timeout <= 0:
            raise ValueError("--delay cannot be negative and --timeout must be positive.")
        sizes = [normalize_size(size) for size in args.sizes]
        if len(set(sizes)) != len(sizes):
            raise ValueError("Each selected size must be unique.")
        available_sizes = load_common_sizes(root / "common_motorcycle_tyre_sizes.csv")
        unknown_sizes = sorted(set(sizes) - available_sizes)
        if unknown_sizes:
            raise ValueError(
                "Not in common_motorcycle_tyre_sizes.csv: " + ", ".join(unknown_sizes)
            )

        all_products = []
        for index, size in enumerate(sizes):
            products, truncated = scrape_size(
                size, args.max_pages, args.delay, args.timeout
            )
            all_products.extend(products)
            print(f"{size}: collected {len(products)} tyre prices")
            if truncated:
                print(
                    f"Warning: {size} reached --max-pages; some listing results may be missing.",
                    file=sys.stderr,
                )
            if index + 1 < len(sizes) and args.delay:
                time.sleep(args.delay)

        args.database.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(args.database) as connection:
            initialize_database(connection)
            save_observations(connection, date.today().isoformat(), all_products)
        print(f"Saved {len(all_products)} observations to {args.database}")
    except (OSError, sqlite3.Error, RuntimeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
