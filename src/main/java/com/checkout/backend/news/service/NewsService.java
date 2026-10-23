package com.checkout.backend.news.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.quote.model.AssetQuote;
import com.checkout.backend.investment_portfolio.asset.quote.repository.AssetQuoteRepository;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.investment_portfolio.service.PortfolioService;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.news.NewsClient;
import com.checkout.backend.news.NewsClient.NewsPage;
import com.checkout.backend.news.NewsClient.ProviderNews;
import com.checkout.backend.news.dto.NewsResponses.NewsAssetResponse;
import com.checkout.backend.news.dto.NewsResponses.NewsItemResponse;
import com.checkout.backend.news.dto.NewsResponses.NewsPageResponse;
import com.checkout.backend.news.dto.NewsResponses.TrendingAssetResponse;
import com.checkout.backend.user.model.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * El mural de noticias del portafolio.
 *
 * Cada pagina se guarda 5 minutos: el plan gratuito de Alpaca permite 200
 * pedidos por minuto y los comparte con los precios, y una clase entera abriendo
 * el mural a la vez los agotaria. Si Alpaca falla y hay una copia vieja, se
 * muestra esa: una noticia de hace 10 minutos sirve mas que un error.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    static final int PAGE_SIZE = 20;
    private static final int TRENDING_PAGE = 50;
    private static final int TRENDING_SIZE = 5;
    private static final int MAX_ASSETS_PER_ITEM = 4;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED_PAGES = 300;

    private record Cached(NewsPage page, Instant fetchedAt) {
    }

    private final NewsClient client;
    private final AssetRepository assets;
    private final AssetQuoteRepository quotes;
    private final PortfolioService portfolio;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public NewsService(NewsClient client, AssetRepository assets, AssetQuoteRepository quotes,
                       PortfolioService portfolio, Clock clock) {
        this.client = client;
        this.assets = assets;
        this.quotes = quotes;
        this.portfolio = portfolio;
        this.clock = clock;
    }

    /**
     * @param scope  "mine" (lo que tiene el usuario), "market" o "crypto"
     * @param symbol un activo del catalogo; si viene, manda sobre el alcance
     *
     * Sin @Transactional a proposito: no se retiene una conexion de la base
     * mientras se espera a Alpaca. Cada consulta al catalogo es corta y suelta.
     */
    public NewsPageResponse feed(User user, String scope, String symbol, String pageToken) {
        List<String> symbols;
        if (symbol != null && !symbol.isBlank()) {
            Asset asset = assets.findBySymbolIgnoreCase(symbol.strip())
                    .orElseThrow(() -> new ResourceNotFoundException("Activo", symbol));
            symbols = List.of(providerSymbol(asset));
        } else {
            switch (scope == null ? "market" : scope) {
                case "mine" -> {
                    List<Long> held = portfolio.positionAssetIds(user);
                    if (held.isEmpty()) {
                        return new NewsPageResponse(List.of(), null);
                    }
                    symbols = assets.findAllById(held).stream().map(NewsService::providerSymbol).sorted().toList();
                }
                case "crypto" -> symbols = assets.findBySource(AssetSource.COINBASE).stream()
                        .filter(asset -> Boolean.TRUE.equals(asset.getActive()))
                        .map(NewsService::providerSymbol)
                        .sorted()
                        .toList();
                case "market" -> symbols = List.of();
                default -> throw new InvalidRequestException("El alcance tiene que ser mine, market o crypto.");
            }
        }
        NewsPage page = page(symbols, pageToken, PAGE_SIZE);
        return new NewsPageResponse(present(page.items(), Set.copyOf(symbols)), page.nextPageToken());
    }

    /** Los activos del catalogo que mas nombran las ultimas 100 noticias del mercado. */
    public List<TrendingAssetResponse> trending() {
        NewsPage first = page(List.of(), null, TRENDING_PAGE);
        List<ProviderNews> sample = new ArrayList<>(first.items());
        if (first.nextPageToken() != null) {
            sample.addAll(page(List.of(), first.nextPageToken(), TRENDING_PAGE).items());
        }
        Map<String, Asset> catalog = resolve(sample);
        Map<Asset, Integer> mentions = new HashMap<>();
        for (ProviderNews item : sample) {
            item.symbols().stream()
                    .map(catalog::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(asset -> mentions.merge(asset, 1, Integer::sum));
        }
        return mentions.entrySet().stream()
                .sorted(Map.Entry.<Asset, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().getSymbol()))
                .limit(TRENDING_SIZE)
                .map(entry -> new TrendingAssetResponse(entry.getKey().getId(), entry.getKey().getSymbol(),
                        entry.getKey().getName(), entry.getValue()))
                .toList();
    }

    private NewsPage page(List<String> symbols, String pageToken, int limit) {
        String key = String.join(",", symbols) + "|" + pageToken + "|" + limit;
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.fetchedAt().isAfter(now.minus(TTL))) {
            return cached.page();
        }
        try {
            NewsPage fresh = client.latest(symbols, pageToken, limit);
            if (cache.size() >= MAX_CACHED_PAGES) {
                cache.clear();
            }
            cache.put(key, new Cached(fresh, now));
            return fresh;
        } catch (MarketDataUnavailableException e) {
            if (cached != null) {
                log.warn("Sin noticias nuevas; se muestran las de {}", cached.fetchedAt());
                return cached.page();
            }
            throw e;
        }
    }

    /** @param pinned los simbolos pedidos: van primero, para que el tope de activos por nota no los deje fuera */
    private List<NewsItemResponse> present(List<ProviderNews> items, Set<String> pinned) {
        Map<String, Asset> catalog = resolve(items);
        Map<Long, BigDecimal> changes = quotes.findByAssetIdIn(
                        catalog.values().stream().map(Asset::getId).collect(Collectors.toSet()))
                .stream()
                .filter(quote -> quote.getChangePercent() != null)
                .collect(Collectors.toMap(quote -> quote.getAsset().getId(), AssetQuote::getChangePercent));

        return items.stream().map(item -> {
            Map<Long, NewsAssetResponse> mentioned = new LinkedHashMap<>();
            List<String> ordered = item.symbols().stream()
                    .sorted(Comparator.comparing(symbol -> !pinned.contains(symbol)))
                    .toList();
            for (String symbol : ordered) {
                Asset asset = catalog.get(symbol);
                if (asset != null && mentioned.size() < MAX_ASSETS_PER_ITEM) {
                    mentioned.putIfAbsent(asset.getId(), new NewsAssetResponse(asset.getId(), asset.getSymbol(),
                            asset.getName(), changes.get(asset.getId())));
                }
            }
            return new NewsItemResponse(item.id(), item.headline(), item.summary(), item.source(), item.author(),
                    item.url(), item.imageUrl(), item.publishedAt(), item.updatedAt(), List.copyOf(mentioned.values()));
        }).toList();
    }

    /**
     * Los simbolos de las noticias que existen en el catalogo. La cripto llega
     * como BTCUSD y en el catalogo es BTC-USD; un simbolo que no esta en el
     * catalogo no se puede enlazar y se descarta.
     */
    private Map<String, Asset> resolve(List<ProviderNews> items) {
        Set<String> wanted = new HashSet<>();
        for (ProviderNews item : items) {
            for (String symbol : item.symbols()) {
                String upper = symbol.toUpperCase(Locale.ROOT);
                wanted.add(upper);
                if (upper.endsWith("USD") && upper.length() > 3) {
                    wanted.add(upper.substring(0, upper.length() - 3) + "-USD");
                }
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return assets.findBySymbolInAndActiveTrue(wanted).stream()
                .collect(Collectors.toMap(NewsService::providerSymbol, asset -> asset,
                        (first, second) -> first.getSource() == AssetSource.COINBASE ? first : second));
    }

    private static String providerSymbol(Asset asset) {
        return asset.getSource() == AssetSource.COINBASE ? asset.getSymbol().replace("-", "") : asset.getSymbol();
    }

}
