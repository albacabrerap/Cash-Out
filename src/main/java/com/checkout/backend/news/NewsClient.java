package com.checkout.backend.news;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Proveedor de noticias del mercado. Igual que MarketDataClient, es una interfaz
 * para que el resto del backend no dependa de Alpaca y los tests usen uno falso;
 * cualquier fallo sale como MarketDataUnavailableException.
 */
public interface NewsClient {

    /**
     * Las noticias mas recientes primero.
     *
     * @param symbols   simbolos como los entiende el proveedor (BTCUSD, no BTC-USD);
     *                  vacio para todo el mercado
     * @param pageToken el de la pagina anterior, o null para la primera
     */
    NewsPage latest(Collection<String> symbols, String pageToken, int limit);

    record NewsPage(List<ProviderNews> items, String nextPageToken) {
    }

    /**
     * @param summary  puede venir vacio: no todas las notas traen resumen
     * @param url      solo http o https; null si el proveedor mando otra cosa
     * @param updatedAt la ultima edicion; Alpaca ordena las paginas por esta fecha, no por la de publicacion
     * @param imageUrl  null si la nota no trae imagen
     */
    record ProviderNews(long id, String headline, String summary, String source, String author, String url,
                        Instant publishedAt, Instant updatedAt, String imageUrl, List<String> symbols) {
    }

}
