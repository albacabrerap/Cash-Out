package com.checkout.backend.investment_portfolio.watchlist.controller;

import com.checkout.backend.investment_portfolio.asset.dto.AssetResponse;
import com.checkout.backend.investment_portfolio.watchlist.service.WatchlistService;
import com.checkout.backend.user.service.CurrentUserProvider;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los favoritos del usuario autenticado. El activo va en la ruta y PUT/DELETE
 * son idempotentes, asi que la estrella de la web es un simple interruptor.
 */
@RestController
@RequestMapping("/watchlist")
public class WatchlistController {

    private final WatchlistService watchlist;
    private final CurrentUserProvider currentUser;

    public WatchlistController(WatchlistService watchlist, CurrentUserProvider currentUser) {
        this.watchlist = watchlist;
        this.currentUser = currentUser;
    }

    /**
     * GET /api/v1/watchlist — del mas reciente al mas antiguo. Los precios se
     * piden aparte con /assets/quotes, igual que una pagina del catalogo.
     */
    @GetMapping
    public ResponseEntity<List<AssetResponse>> list() {
        return ResponseEntity.ok(watchlist.list(currentUser.requireCurrentUser()));
    }

    /** PUT /api/v1/watchlist/{assetId} — 204 aunque ya estuviera. */
    @PutMapping("/{assetId}")
    public ResponseEntity<Void> add(@PathVariable Long assetId) {
        watchlist.add(currentUser.requireCurrentUser(), assetId);
        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/v1/watchlist/{assetId} — 204 aunque no estuviera. */
    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> remove(@PathVariable Long assetId) {
        watchlist.remove(currentUser.requireCurrentUser(), assetId);
        return ResponseEntity.noContent().build();
    }

}
