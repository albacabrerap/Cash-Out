package com.checkout.backend.investment_portfolio.trade_order.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.service.AssetService;
import com.checkout.backend.investment_portfolio.service.PortfolioService;
import com.checkout.backend.investment_portfolio.trade_order.dto.TradeOrderRequest;
import com.checkout.backend.investment_portfolio.trade_order.event.TradeOrderExecutedEvent;
import com.checkout.backend.investment_portfolio.trade_order.dto.TradeOrderResponse;
import com.checkout.backend.investment_portfolio.trade_order.model.OrderSide;
import com.checkout.backend.investment_portfolio.trade_order.model.OrderStatus;
import com.checkout.backend.investment_portfolio.trade_order.model.TradeOrder;
import com.checkout.backend.investment_portfolio.trade_order.repository.TradeOrderRepository;
import com.checkout.backend.token_wallet.service.TokenWalletService;
import com.checkout.backend.token_wallet.tktransaction.model.TokenReason;
import com.checkout.backend.user.model.User;
import com.checkout.backend.web.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.modelmapper.ModelMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulador de ordenes de compra y venta.
 *
 * Ejecuta al instante contra la cotizacion vigente: no hay libro de ordenes ni
 * contraparte, porque lo que se simula es la experiencia de invertir, no un
 * mercado. Por eso una orden nace y muere en la misma peticion.
 *
 * Idempotencia. El cliente manda un clientOrderId, que tiene UNIQUE en la tabla.
 * Si reintenta porque no le llego la respuesta, la segunda llamada devuelve la
 * orden que ya se ejecuto en vez de comprar otra vez. Sin eso, un toque doble o
 * una red inestable cuestan dinero al usuario.
 *
 * Una orden que no se puede ejecutar se guarda como REJECTED con su motivo, en
 * vez de desaparecer en un error. El usuario ve en su historial que lo intento y
 * por que no salio, y eso vale tanto como ver las que si salieron.
 */
@Service
public class TradeOrderService {

    /**
     * Fichas por unidad de la moneda del activo.
     *
     * Es la tasa del simulador y hoy es uno a uno. Se guarda en cada orden
     * porque, el dia que cambie, tokensMoved de una orden vieja solo se puede
     * reproducir con la tasa que estaba vigente al ejecutarla.
     */
    private static final BigDecimal TOKEN_RATE = BigDecimal.ONE;

    private final TradeOrderRepository orderRepository;
    private final AssetService assetService;
    private final PortfolioService portfolioService;
    private final TokenWalletService walletService;
    private final ApplicationEventPublisher events;
    private final ModelMapper mapper;

    public TradeOrderService(TradeOrderRepository orderRepository,
                             AssetService assetService,
                             PortfolioService portfolioService,
                             TokenWalletService walletService,
                             ApplicationEventPublisher events,
                             ModelMapper mapper) {
        this.orderRepository = orderRepository;
        this.assetService = assetService;
        this.portfolioService = portfolioService;
        this.walletService = walletService;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * Coloca una orden y la resuelve.
     *
     * El orden de los pasos importa: primero se comprueba la idempotencia, luego
     * se resuelve el activo y su precio, y solo al final se mueven fichas y
     * posiciones. Asi ninguna validacion que pueda fallar ocurre despues de haber
     * tocado un saldo.
     *
     * El rechazo se decide preguntando, antes de escribir nada. Antes se hacia al
     * reves —llamar a record o a applySell y atrapar su InvalidRequestException
     * para marcar la orden REJECTED— y no funcionaba. El motivo merece quedar
     * escrito para que nadie lo reintroduzca: esos metodos son @Transactional de
     * otros beans, asi que se invocan por proxy y participan en esta misma
     * transaccion. Cuando uno lanza, el interceptor transaccional de Spring marca
     * la transaccion compartida como rollback-only antes de que la excepcion
     * llegue al catch. El catch la atrapaba y seguia, pero el commit ya estaba
     * condenado y salia UnexpectedRollbackException: el cliente recibia un 500 en
     * lugar de su orden rechazada.
     *
     * Preguntar primero elimina ese camino: en el rechazo no se lanza ninguna
     * excepcion, nada marca la transaccion, y la orden REJECTED se guarda y se
     * devuelve con normalidad.
     */
    @Transactional
    public TradeOrderResponse place(User user, TradeOrderRequest request) {
        var alreadyPlaced = orderRepository
                .findByClientOrderIdAndUserId(request.getClientOrderId(), user.getId());
        if (alreadyPlaced.isPresent()) {
            return toResponse(alreadyPlaced.get());
        }

        Asset asset = assetService.findTradable(request.getAssetId());

        TradeOrder order = TradeOrder.builder()
                .user(user)
                .asset(asset)
                .clientOrderId(request.getClientOrderId())
                .side(request.getSide())
                .quantity(request.getQuantity())
                .status(OrderStatus.PENDING)
                .build();

        BigDecimal price = assetService.requireQuote(asset).getPrice();
        BigDecimal tokens = request.getQuantity().multiply(price).multiply(TOKEN_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        order.setExecutionPrice(price);
        order.setTokenRate(TOKEN_RATE);
        order.setTokensMoved(tokens);

        // Fichas insuficientes o posicion insuficiente no son errores del cliente
        // que haya que devolverle como un 400: son el resultado de la orden. Se
        // guarda rechazada con su motivo, y el usuario la ve en su historial igual
        // que las que si salieron.
        // Una orden que redondea a cero fichas no puede asentarse en el libro (un
        // movimiento de cero no existe), asi que se rechaza aqui con su motivo.
        Optional<String> rejection = tokens.signum() == 0
                ? Optional.of("La orden vale menos de 0.01 fichas. Aumenta la cantidad.")
                : request.getSide() == OrderSide.BUY
                        ? walletService.reasonToRejectSpending(user, tokens)
                        : portfolioService.reasonToRejectSell(user, asset, request.getQuantity());

        if (rejection.isPresent()) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectionReason(rejection.get());
            order.setTokensMoved(BigDecimal.ZERO);
            return toResponse(orderRepository.save(order));
        }

        // La orden se guarda antes de mover fichas para que su id pueda viajar
        // como referencia del asiento. Antes se pasaba null, y eso dejaba el libro
        // de fichas sin forma de rastrear un movimiento hasta la orden que lo
        // causo, y sin la idempotencia que da el UNIQUE(reason, reference_id).
        TradeOrder saved = orderRepository.save(order);

        if (request.getSide() == OrderSide.BUY) {
            walletService.record(user, tokens.negate(), TokenReason.INVESTMENT, saved.getId());
            portfolioService.applyBuy(user, asset, request.getQuantity(), price, tokens);
        } else {
            portfolioService.applySell(user, asset, request.getQuantity(), price);
            walletService.record(user, tokens, TokenReason.INVESTMENT, saved.getId());
        }

        saved.setStatus(OrderStatus.EXECUTED);
        saved.setExecutedAt(LocalDateTime.now());

        // Se anuncia el hecho, no se notifica al usuario desde aqui: quien ejecuta
        // una orden no tiene por que saber quien esta interesado en que se ejecuto.
        // El envio ocurre despues del commit, en su listener.
        events.publishEvent(new TradeOrderExecutedEvent(
                saved.getId(), user.getId(), user.getEmail(), asset.getSymbol(),
                saved.getSide(), saved.getQuantity(), price, tokens));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeOrderResponse> list(User user, Pageable pageable) {
        return PageResponse.of(orderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public TradeOrderResponse get(User user, Long id) {
        return toResponse(findOwned(user, id));
    }

    /**
     * Cancela una orden que todavia no se resolvio.
     *
     * Con ejecucion inmediata esto casi nunca aplica, porque una orden sale de
     * place ya ejecutada o rechazada. Existe igual porque el modelo declara el
     * estado CANCELLED y porque el dia que la ejecucion pase a ser diferida
     * — una cola, un job — este es el camino que el cliente ya conoce.
     *
     * Una orden ya ejecutada no se cancela: deshacerla significaria revertir
     * fichas y posiciones a precios que ya cambiaron.
     */
    @Transactional
    public TradeOrderResponse cancel(User user, Long id) {
        TradeOrder order = findOwned(user, id);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidRequestException(
                    "Solo se puede cancelar una orden pendiente. Esta esta " + order.getStatus() + ".");
        }

        order.setStatus(OrderStatus.CANCELLED);
        return toResponse(order);
    }

    private TradeOrder findOwned(User user, Long id) {
        return orderRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Orden", id));
    }

    private TradeOrderResponse toResponse(TradeOrder order) {
        return mapper.map(order, TradeOrderResponse.class);
    }

}
