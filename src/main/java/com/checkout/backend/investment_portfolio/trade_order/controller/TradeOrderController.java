package com.checkout.backend.investment_portfolio.trade_order.controller;

import com.checkout.backend.investment_portfolio.trade_order.dto.TradeOrderRequest;
import com.checkout.backend.investment_portfolio.trade_order.dto.TradeOrderResponse;
import com.checkout.backend.investment_portfolio.trade_order.service.TradeOrderService;
import com.checkout.backend.market.service.MarketQuoteService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import com.checkout.backend.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Ordenes de compra y venta del usuario autenticado.
 *
 * Es la unica escritura del modulo de inversion: la cartera y las posiciones se
 * derivan de lo que pasa aqui. Concentrar la escritura en un solo sitio es lo
 * que permite que el resto sea de solo lectura.
 *
 * El cliente manda un clientOrderId y la ruta es idempotente: reintentar la
 * misma orden devuelve la que ya se ejecuto en vez de comprar otra vez. Sin eso,
 * un toque doble o una red inestable cuestan dinero al usuario.
 */
@RestController
@RequestMapping("/orders")
public class TradeOrderController {

    private final TradeOrderService orderService;
    private final CurrentUserProvider currentUser;
    private final MarketQuoteService marketQuotes;

    public TradeOrderController(TradeOrderService orderService,
                                CurrentUserProvider currentUser,
                                MarketQuoteService marketQuotes) {
        this.orderService = orderService;
        this.currentUser = currentUser;
        this.marketQuotes = marketQuotes;
    }

    /** GET /api/v1/orders */
    @GetMapping
    public ResponseEntity<PageResponse<TradeOrderResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(orderService.list(currentUser.requireCurrentUser(), pageable));
    }

    /** GET /api/v1/orders/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<TradeOrderResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.get(currentUser.requireCurrentUser(), id));
    }

    /**
     * POST /api/v1/orders
     *
     * Coloca la orden y la resuelve al instante contra la cotizacion vigente.
     *
     * Responde 201 tambien cuando la orden sale rechazada: el recurso se creo y
     * queda en el historial con su motivo. Que no se ejecutara es el resultado de
     * la operacion, no un fallo de la peticion, y el cliente lo lee en el campo
     * status.
     */
    @PostMapping
    public ResponseEntity<TradeOrderResponse> place(@Valid @RequestBody TradeOrderRequest request) {
        // El precio se refresca aqui, antes de abrir la transaccion de la orden:
        // esperar la red con la base tomada bloquearia la billetera del usuario.
        marketQuotes.refreshIfStale(request.getAssetId());
        TradeOrderResponse placed = orderService.place(currentUser.requireCurrentUser(), request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(placed.getId())
                .toUri();

        return ResponseEntity.created(location).body(placed);
    }

    /**
     * DELETE /api/v1/orders/{id}
     *
     * Cancela una orden pendiente. Devuelve el recurso y no 204 porque el cambio
     * de estado es justo lo que el cliente necesita ver.
     *
     * Una orden ya ejecutada no se cancela: deshacerla significaria revertir
     * fichas y posiciones a precios que ya cambiaron.
     */
    /**
     * POST /api/v1/orders/{id}/cancel
     *
     * Es POST sobre un subrecurso y no DELETE sobre la orden, porque cancelar no
     * borra nada: cambia el estado a CANCELLED y la orden sigue en el historial.
     * Un DELETE que no elimina y que ademas devuelve cuerpo obliga a cualquier
     * cliente a aprender una excepcion a la regla.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<TradeOrderResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.cancel(currentUser.requireCurrentUser(), id));
    }

}
