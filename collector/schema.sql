CREATE TABLE IF NOT EXISTS products (
    product_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    size TEXT NOT NULL,
    url TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS price_observations (
    observed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    price_eur NUMERIC NOT NULL,
    PRIMARY KEY (observed_at, product_id)
);

CREATE INDEX IF NOT EXISTS price_observations_by_product
ON price_observations(product_id, observed_at);
