package com.checkout.backend.market.coinbase;

import com.checkout.backend.market.coinbase.CoinbaseTradeStream.StreamTrade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** La lectura de los mensajes del WebSocket de Coinbase, con su forma real. */
class CoinbaseTradeStreamTest {

    @Test
    @DisplayName("Un mensaje de market_trades da cada operacion con su par, precio y hora")
    void parsesTrades() {
        List<StreamTrade> trades = CoinbaseTradeStream.parseTrades("""
                {"channel":"market_trades","timestamp":"2026-10-17T20:14:19.5Z","sequence_num":3,
                 "events":[{"type":"update","trades":[
                   {"trade_id":"1","product_id":"BTC-USD","price":"65000.10","size":"0.01","side":"BUY",
                    "time":"2026-10-17T20:14:19.123456Z"},
                   {"trade_id":"2","product_id":"ETH-USD","price":"2500.5","size":"1","side":"SELL",
                    "time":"2026-10-17T20:14:19.2Z"}]}]}
                """);

        assertThat(trades).containsExactly(
                new StreamTrade("BTC-USD", new BigDecimal("65000.10"), Instant.parse("2026-10-17T20:14:19.123456Z")),
                new StreamTrade("ETH-USD", new BigDecimal("2500.5"), Instant.parse("2026-10-17T20:14:19.2Z")));
    }

    @Test
    @DisplayName("Latidos, confirmaciones de suscripcion y mensajes rotos no dan operaciones")
    void ignoresOtherMessages() {
        assertThat(CoinbaseTradeStream.parseTrades("""
                {"channel":"heartbeats","events":[{"current_time":"2026-10-17","heartbeat_counter":3}]}
                """)).isEmpty();
        assertThat(CoinbaseTradeStream.parseTrades("""
                {"channel":"subscriptions","events":[{"subscriptions":{"market_trades":["BTC-USD"]}}]}
                """)).isEmpty();
        assertThat(CoinbaseTradeStream.parseTrades("{no es json")).isEmpty();
        assertThat(CoinbaseTradeStream.parseTrades("""
                {"channel":"market_trades","events":[{"type":"update","trades":[{"product_id":"BTC-USD","price":"x"}]}]}
                """)).isEmpty();
    }

}
