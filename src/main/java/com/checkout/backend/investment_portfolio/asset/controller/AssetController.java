package com.checkout.backend.investment_portfolio.asset.controller;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.investment_portfolio.asset.dto.AssetRequest;
import com.checkout.backend.investment_portfolio.asset.dto.AssetResponse;
import com.checkout.backend.investment_portfolio.asset.history.dto.AssetPriceResponse;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.quote.dto.AssetQuoteResponse;
import com.checkout.backend.investment_portfolio.asset.quote.dto.AssetQuoteUpdateRequest;
import com.checkout.backend.investment_portfolio.asset.service.AssetService;
import com.checkout.backend.market.live.LivePriceService;
import com.checkout.backend.market.service.AssetCatalogSyncService;
import com.checkout.backend.market.service.MarketQuoteService;
import com.checkout.backend.market.service.PriceHistorySyncService;
import com.checkout.backend.web.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Catalogo de activos, con su cotizacion y su historico como sub-recursos.
 *
 * La cotizacion y el historico van anidados bajo /assets/{id} y no como
 * controllers sueltos porque ninguno de los dos existe sin su activo: pedir
 * /quotes/{id} obligaria a conocer un identificador que no es el que el cliente
 * tiene en la mano.
 *
 * Escribir el precio es de ADMIN, y es la restriccion mas importante del modulo:
 * ese precio es la base sobre la que se valora la cartera de todos, asi que
 * quien pueda escribirlo puede inflar su propia posicion. Por eso solo existe
 * para los activos cargados a mano: los de Alpaca toman su precio del mercado.
 */
@RestController
@RequestMapping("/assets")
public class AssetController {

    /** Una pagina del catalogo, con margen: el listado admite hasta 100 por pagina en la web. */
    private static final int MAX_QUOTES = 100;

    private final AssetService assetService;
    private final AssetCatalogSyncService catalogSync;
    private final MarketQuoteService marketQuotes;
    private final PriceHistorySyncService historySync;
    private final LivePriceService livePrices;

    public AssetController(AssetService assetService,
                           AssetCatalogSyncService catalogSync,
                           MarketQuoteService marketQuotes,
                           PriceHistorySyncService historySync,
                           LivePriceService livePrices) {
        this.assetService = assetService;
        this.catalogSync = catalogSync;
        this.marketQuotes = marketQuotes;
        this.historySync = historySync;
        this.livePrices = livePrices;
    }

    /**
     * GET /api/v1/assets?search=&type=&letter=&page=&size=
     *
     * Paginado: el catalogo del proveedor tiene unas 14 mil acciones.
     * {@code search} busca en simbolo y nombre, con el simbolo exacto primero.
     * {@code letter} deja solo los simbolos que empiezan con esa letra.
     * {@code includeInactive} incluye las dadas de baja y es solo de ADMIN.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AssetResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AssetType type,
            @RequestParam(required = false) String letter,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(includeInactive
                ? assetService.searchAll(search, type, letter, pageable)
                : assetService.searchActive(search, type, letter, pageable));
    }

    /**
     * GET /api/v1/assets/quotes?ids=1,2,3
     *
     * Las cotizaciones de una pagina del catalogo con un solo pedido al
     * proveedor, en vez de uno por fila. Los activos sin precio no aparecen.
     */
    @GetMapping("/quotes")
    public ResponseEntity<List<AssetQuoteResponse>> quotes(@RequestParam List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.size() > MAX_QUOTES) {
            throw new InvalidRequestException("Se pueden pedir hasta " + MAX_QUOTES + " cotizaciones a la vez.");
        }
        marketQuotes.refreshStale(distinct);
        return ResponseEntity.ok(assetService.getQuotes(distinct));
    }

    /**
     * POST /api/v1/assets/sync
     *
     * Trae el catalogo de acciones de Alpaca: agrega las nuevas, actualiza las
     * existentes y da de baja las que el proveedor ya no lista. Un activo de
     * Alpaca dado de baja a mano vuelve a activarse aqui si el proveedor lo lista.
     */
    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AssetCatalogSyncService.SyncResult> sync() {
        return ResponseEntity.ok(catalogSync.sync());
    }

    /** GET /api/v1/assets/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<AssetResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(assetService.get(id));
    }

    /** POST /api/v1/assets */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AssetResponse> create(@Valid @RequestBody AssetRequest request) {
        AssetResponse created = assetService.create(request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    /**
     * DELETE /api/v1/assets/{id}
     *
     * Baja logica. Las posiciones abiertas sobre este activo siguen valorandose;
     * lo que deja de poder hacerse es abrir nuevas.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        assetService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/v1/assets/{id}/quote */
    @GetMapping("/{id}/quote")
    public ResponseEntity<AssetQuoteResponse> getQuote(@PathVariable Long id) {
        marketQuotes.refreshIfStale(id);
        return ResponseEntity.ok(assetService.getQuote(id));
    }

    /**
     * PUT /api/v1/assets/{id}/quote
     *
     * Es PUT y no POST porque hay una sola cotizacion por activo y esto la
     * reemplaza. Guarda ademas el cierre del dia, para que el historico no quede
     * con huecos cuando el precio se sobrescriba.
     */
    @PutMapping("/{id}/quote")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AssetQuoteResponse> updateQuote(
            @PathVariable Long id,
            @Valid @RequestBody AssetQuoteUpdateRequest request) {
        return ResponseEntity.ok(assetService.updateQuote(id, request.price()));
    }

    /**
     * GET /api/v1/assets/{id}/price-history?from=2026-01-01&to=2026-06-30
     *
     * Los cierres diarios que alimentan el grafico.
     */
    @GetMapping("/{id}/price-history")
    public ResponseEntity<List<AssetPriceResponse>> priceHistory(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        historySync.ensureHistory(id);
        return ResponseEntity.ok(assetService.priceHistory(id, from, to));
    }

    /**
     * GET /api/v1/assets/{id}/live
     *
     * El precio de una cripto segundo a segundo, como eventos del servidor (SSE):
     * primero "history" con los ultimos 15 minutos y despues un "tick" por segundo.
     */
    @GetMapping("/{id}/live")
    public SseEmitter live(@PathVariable Long id) {
        AssetResponse asset = assetService.get(id);
        if (asset.getSource() != AssetSource.COINBASE) {
            throw new InvalidRequestException("Solo las criptomonedas tienen precio en vivo.");
        }
        return livePrices.subscribe(asset.getSymbol());
    }

}
