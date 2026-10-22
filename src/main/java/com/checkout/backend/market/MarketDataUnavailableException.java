package com.checkout.backend.market;

import com.checkout.backend.exceptions.ApiException;
import org.springframework.http.HttpStatus;

/**
 * El proveedor de precios no respondio, respondio con error o no esta
 * configurado. Sale como 503: el pedido era valido, lo que falta es una fuente
 * externa, y reintentar mas tarde puede funcionar.
 *
 * El mensaje es para el usuario; el detalle tecnico viaja en la causa y se
 * registra en el log, nunca en la respuesta (podria incluir la URL del proveedor).
 */
public class MarketDataUnavailableException extends ApiException {

    public MarketDataUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

}
