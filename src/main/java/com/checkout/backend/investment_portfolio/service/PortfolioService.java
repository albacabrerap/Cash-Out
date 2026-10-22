package com.checkout.backend.investment_portfolio.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.service.AssetService;
import com.checkout.backend.investment_portfolio.dto.PortfolioResponse;
import com.checkout.backend.investment_portfolio.model.InvestmentPortfolio;
import com.checkout.backend.investment_portfolio.position.dto.PositionResponse;
import com.checkout.backend.investment_portfolio.position.model.PortfolioPosition;
import com.checkout.backend.investment_portfolio.position.repository.PortfolioPositionRepository;
import com.checkout.backend.investment_portfolio.repository.InvestmentPortfolioRepository;
import com.checkout.backend.user.model.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cartera simulada del usuario y las posiciones que la componen.
 *
 * Igual que el monedero, no tiene escritura por API. Una posicion no se crea ni
 * se edita: aparece porque se ejecuto una orden de compra y desaparece cuando se
 * vende entera. Permitir editarla directamente seria poder darse acciones sin
 * pagarlas.
 *
 * El valor de mercado se calcula al leer, con la cotizacion del momento, en vez
 * de guardarse actualizado. Guardarlo obligaria a recorrer todas las carteras
 * cada vez que cambia un precio, y bastaria con que una actualizacion fallara
 * para que un usuario viera un valor que ya no es cierto.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final InvestmentPortfolioRepository portfolioRepository;
    private final PortfolioPositionRepository positionRepository;
    private final AssetService assetService;
    private final ModelMapper mapper;

    public PortfolioService(InvestmentPortfolioRepository portfolioRepository,
                            PortfolioPositionRepository positionRepository,
                            AssetService assetService,
                            ModelMapper mapper) {
        this.portfolioRepository = portfolioRepository;
        this.positionRepository = positionRepository;
        this.assetService = assetService;
        this.mapper = mapper;
    }

    @Transactional
    public InvestmentPortfolio getOrCreate(User user) {
        return portfolioRepository.findByUserId(user.getId())
                .orElseGet(() -> portfolioRepository.save(
                        InvestmentPortfolio.builder()
                                .user(user)
                                .investedTokens(BigDecimal.ZERO)
                                .simulatedValue(BigDecimal.ZERO)
                                .build()));
    }

    /**
     * La cartera con sus posiciones ya valoradas.
     *
     * simulatedValue se recalcula y se guarda de paso: asi el dato persistido no
     * se queda atras respecto a lo que el usuario acaba de ver.
     */
    @Transactional
    public PortfolioResponse getSummary(User user) {
        InvestmentPortfolio portfolio = getOrCreate(user);

        List<PositionResponse> positions =
                valuePositions(positionRepository.findByPortfolioId(portfolio.getId()));

        BigDecimal marketValue = positions.stream()
                .map(PositionResponse::getMarketValue)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        portfolio.setSimulatedValue(marketValue);

        PortfolioResponse response = mapper.map(portfolio, PortfolioResponse.class);
        response.setPositions(positions);
        // El mapper deja unrealizedPnl en null porque no existe en la entidad:
        // es la diferencia entre lo que valen hoy las posiciones y lo que costo
        // abrirlas.
        response.setUnrealizedPnl(marketValue.subtract(portfolio.getInvestedTokens()));
        return response;
    }

    /** Los activos en los que el usuario tiene posicion, para refrescar sus precios antes de valorar. */
    @Transactional(readOnly = true)
    public List<Long> positionAssetIds(User user) {
        return portfolioRepository.findByUserId(user.getId())
                .map(portfolio -> positionRepository.findByPortfolioId(portfolio.getId()).stream()
                        .map(position -> position.getAsset().getId())
                        .distinct()
                        .toList())
                .orElse(List.of());
    }

    @Transactional
    public List<PositionResponse> listPositions(User user) {
        return valuePositions(positionRepository.findByPortfolioId(getOrCreate(user).getId()));
    }

    /**
     * Valora una lista de posiciones con dos consultas en total.
     *
     * Antes cada posicion resolvia su activo y su cotizacion por separado, asi que
     * una cartera de veinte posiciones costaba cuarenta y una consultas. El activo
     * ya viene cargado por el @EntityGraph del repositorio, y aqui las
     * cotizaciones se traen todas juntas y se indexan por activo.
     *
     * El mapa puede no tener entrada para un activo, y eso es informacion, no un
     * error: un activo sin cotizacion deja sus tres campos derivados en null en
     * vez de en cero, porque un valor desconocido y un valor cero son cosas
     * distintas y mostrar cero haria creer que la posicion no vale nada.
     */
    private List<PositionResponse> valuePositions(List<PortfolioPosition> positions) {
        if (positions.isEmpty()) {
            return List.of();
        }

        Map<Long, BigDecimal> pricesByAsset = assetService
                .currentPrices(positions.stream()
                        .map(position -> position.getAsset().getId())
                        .distinct()
                        .toList());

        return positions.stream()
                .map(position -> toPositionResponse(
                        position, pricesByAsset.get(position.getAsset().getId())))
                .toList();
    }

    /**
     * Suma una compra a la posicion del activo, creandola si es la primera.
     *
     * El coste medio se recalcula ponderando lo que ya habia con lo que entra.
     * Es lo que permite despues decir si se gana o se pierde: sin coste medio, el
     * valor de mercado es un numero sin referencia.
     */
    @Transactional
    public void applyBuy(User user, Asset asset, BigDecimal quantity, BigDecimal price,
                         BigDecimal tokensSpent) {
        InvestmentPortfolio portfolio = getOrCreate(user);

        PortfolioPosition position = positionRepository
                .findByPortfolioIdAndAssetId(portfolio.getId(), asset.getId())
                .orElse(null);

        if (position == null) {
            position = PortfolioPosition.builder()
                    .portfolio(portfolio)
                    .asset(asset)
                    .quantity(quantity)
                    .averageCost(price)
                    .build();
            portfolio.addPosition(position);
            positionRepository.save(position);
        } else {
            BigDecimal previousCost = position.getQuantity().multiply(position.getAverageCost());
            BigDecimal addedCost = quantity.multiply(price);
            BigDecimal newQuantity = position.getQuantity().add(quantity);

            position.setAverageCost(
                    previousCost.add(addedCost).divide(newQuantity, 4, RoundingMode.HALF_UP));
            position.setQuantity(newQuantity);
        }

        portfolio.setInvestedTokens(portfolio.getInvestedTokens().add(tokensSpent));
    }

    /**
     * Comprueba si el usuario puede vender esa cantidad, sin tocar nada.
     *
     * Misma razon que reasonToRejectSpending en el monedero: el rechazo se
     * decide antes de escribir, para no dejar la transaccion marcada como
     * rollback-only con una excepcion que quien llama pensaba atrapar.
     *
     * @return el motivo del rechazo, o vacio si la venta es posible
     */
    @Transactional(readOnly = true)
    public Optional<String> reasonToRejectSell(User user, Asset asset, BigDecimal quantity) {
        var portfolio = portfolioRepository.findByUserId(user.getId());
        if (portfolio.isEmpty()) {
            return Optional.of("No tienes ninguna posicion en " + asset.getSymbol() + ".");
        }

        var position = positionRepository
                .findByPortfolioIdAndAssetId(portfolio.get().getId(), asset.getId());
        if (position.isEmpty()) {
            return Optional.of("No tienes ninguna posicion en " + asset.getSymbol() + ".");
        }
        if (position.get().getQuantity().compareTo(quantity) < 0) {
            return Optional.of(
                    "Solo tienes " + position.get().getQuantity() + " de " + asset.getSymbol() + ".");
        }
        return Optional.empty();
    }

    /**
     * Descuenta una venta de la posicion.
     *
     * Cuando la cantidad llega a cero la posicion se elimina: una fila con
     * cantidad cero no es informacion, es ruido en el listado.
     *
     * De investedTokens se retira la parte proporcional al coste medio, no lo que
     * se cobro por la venta. Si se restara el importe de la venta, vender con
     * ganancia dejaria el invertido por debajo de lo que realmente queda puesto y
     * el resultado no cuadraria.
     *
     * El coste medio de lo que queda NO se recalcula: vender no cambia a que
     * precio se compro. Lo que si se acumula es el resultado realizado, que es la
     * diferencia entre lo que se cobra y lo que costo la parte vendida. Sin
     * guardarlo, "cuanto he ganado vendiendo" no se puede responder: el beneficio
     * llega al monedero, pero se mezcla con todo lo demas y deja de ser legible.
     */
    @Transactional
    public void applySell(User user, Asset asset, BigDecimal quantity, BigDecimal price) {
        InvestmentPortfolio portfolio = getOrCreate(user);

        PortfolioPosition position = positionRepository
                .findByPortfolioIdAndAssetId(portfolio.getId(), asset.getId())
                .orElseThrow(() -> new InvalidRequestException(
                        "No tienes ninguna posicion en " + asset.getSymbol() + "."));

        if (position.getQuantity().compareTo(quantity) < 0) {
            throw new InvalidRequestException(
                    "Solo tienes " + position.getQuantity() + " de " + asset.getSymbol() + ".");
        }

        BigDecimal releasedCost = quantity.multiply(position.getAverageCost())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal proceeds = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal realized = proceeds.subtract(releasedCost);

        BigDecimal remaining = position.getQuantity().subtract(quantity);
        if (remaining.signum() == 0) {
            portfolio.removePosition(position);
            positionRepository.delete(position);
        } else {
            position.setQuantity(remaining);
        }

        BigDecimal remainingInvested = portfolio.getInvestedTokens().subtract(releasedCost);

        // El max(0) es una red de seguridad, no un calculo: si se activa, el
        // acumulado se habia desviado del coste real de las posiciones, y eso es un
        // bug que hay que poder encontrar. Sin el aviso, la red tapa el sintoma y
        // el numero queda simplemente mal sin que nadie se entere.
        if (remainingInvested.signum() < 0) {
            log.warn("investedTokens quedaria en {} para la cartera {}: el acumulado se "
                            + "desvio del coste de las posiciones. Se ajusta a cero.",
                    remainingInvested, portfolio.getId());
        }

        portfolio.setInvestedTokens(remainingInvested.max(BigDecimal.ZERO));
        portfolio.setRealizedPnl(portfolio.getRealizedPnl().add(realized));
    }

    /**
     * Valora una posicion con el precio que se le pasa.
     *
     * El precio llega ya resuelto en vez de consultarse aqui, que es lo que
     * convertia este metodo en el N+1 de la cartera. Un precio null significa que
     * el activo no tiene cotizacion, y entonces los tres campos derivados se
     * quedan en null en vez de inventar un cero: un valor desconocido y un valor
     * nulo son cosas distintas, y mostrar cero haria creer que la posicion no vale
     * nada.
     */
    private PositionResponse toPositionResponse(PortfolioPosition position, BigDecimal price) {
        PositionResponse response = mapper.map(position, PositionResponse.class);

        if (price != null) {
            BigDecimal marketValue = position.getQuantity().multiply(price)
                    .setScale(2, RoundingMode.HALF_UP);

            response.setCurrentPrice(price);
            response.setMarketValue(marketValue);
            response.setUnrealizedPnl(marketValue.subtract(
                    position.getQuantity().multiply(position.getAverageCost())
                            .setScale(2, RoundingMode.HALF_UP)));
        }

        return response;
    }

}
