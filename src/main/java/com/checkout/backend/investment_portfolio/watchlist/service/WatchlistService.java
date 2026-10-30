package com.checkout.backend.investment_portfolio.watchlist.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.asset.dto.AssetResponse;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.investment_portfolio.watchlist.model.WatchlistItem;
import com.checkout.backend.investment_portfolio.watchlist.repository.WatchlistItemRepository;
import com.checkout.backend.user.model.User;
import java.util.List;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los favoritos del mercado. Agregar y quitar son idempotentes: tocar la
 * estrella dos veces, o reintentar tras un corte de red, deja el mismo estado.
 */
@Service
public class WatchlistService {

    /** Lo mismo que admite el lote de cotizaciones: la lista entera se cotiza de una vez. */
    public static final int MAX_ITEMS = 100;

    private final WatchlistItemRepository items;
    private final AssetRepository assets;
    private final ModelMapper mapper;

    public WatchlistService(WatchlistItemRepository items, AssetRepository assets, ModelMapper mapper) {
        this.items = items;
        this.assets = assets;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> list(User user) {
        return items.findByUserIdWithAsset(user.getId()).stream()
                .map(item -> mapper.map(item.getAsset(), AssetResponse.class))
                .toList();
    }

    @Transactional
    public void add(User user, Long assetId) {
        if (items.existsByUserIdAndAssetId(user.getId(), assetId)) {
            return;
        }
        Asset asset = assets.findById(assetId)
                .filter(Asset::getActive)
                .orElseThrow(() -> new ResourceNotFoundException("Activo", assetId));
        if (items.countByUserId(user.getId()) >= MAX_ITEMS) {
            throw new InvalidRequestException("Puedes seguir hasta " + MAX_ITEMS + " activos. Quita alguno para agregar otro.");
        }
        items.save(new WatchlistItem(user.getId(), asset));
    }

    @Transactional
    public void remove(User user, Long assetId) {
        items.deleteByUserIdAndAssetId(user.getId(), assetId);
    }

}
