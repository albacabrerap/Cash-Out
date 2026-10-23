package com.checkout.backend.investment_portfolio.asset.model;

/**
 * Who maintains the asset and its price.
 *
 * MANUAL assets are created by an admin and priced by hand. ALPACA (US equities)
 * and COINBASE (crypto) come from a provider, and their quote and history are
 * fetched from it: an admin cannot overwrite a price the market already sets.
 */
public enum AssetSource {
    MANUAL,
    ALPACA,
    COINBASE;

    public boolean fromProvider() {
        return this != MANUAL;
    }
}
