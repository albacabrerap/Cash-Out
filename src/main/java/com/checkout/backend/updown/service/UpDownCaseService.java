package com.checkout.backend.updown.service;

import com.checkout.backend.exceptions.DuplicateResourceException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.updown.dto.UpDownCaseRequest;
import com.checkout.backend.updown.dto.UpDownCaseResponse;
import com.checkout.backend.updown.dto.UpDownPreviewResponse;
import com.checkout.backend.updown.model.UpDownCase;
import com.checkout.backend.updown.model.UpDownChoice;
import com.checkout.backend.updown.repository.UpDownCaseRepository;
import com.checkout.backend.updown.service.UpDownPrices.Series;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El banco de casos de "¿Sube o baja?", solo para ADMIN.
 *
 * Al guardar se bajan los cierres del activo: un caso sin 80 cierres antes y
 * 21 despues no se podria jugar, y asi el resultado real queda a la vista de
 * quien escribe la leccion.
 */
@Service
public class UpDownCaseService {

    private final UpDownCaseRepository cases;
    private final UpDownPrices prices;

    public UpDownCaseService(UpDownCaseRepository cases, UpDownPrices prices) {
        this.cases = cases;
        this.prices = prices;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<UpDownCaseResponse> list() {
        return cases.findAllByOrderByCutoffDesc().stream().map(UpDownCaseService::toResponse).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public UpDownPreviewResponse preview(String symbol, LocalDate cutoff) {
        String normalized = normalize(symbol);
        Series series = prices.load(normalized, cutoff);
        double change = round(series.changePercent());
        return new UpDownPreviewResponse(normalized, cutoff.toString(), rounded(series.past()),
                rounded(series.future()), change, UpDownChoice.of(change).name());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UpDownCaseResponse create(UpDownCaseRequest request) {
        String symbol = normalize(request.symbol());
        if (cases.existsBySymbolAndCutoff(symbol, request.cutoff())) {
            throw new DuplicateResourceException("Ya hay un caso de " + symbol + " para el " + request.cutoff() + ".");
        }
        UpDownCase saved = new UpDownCase();
        apply(saved, symbol, request);
        return toResponse(cases.save(saved));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UpDownCaseResponse update(Long id, UpDownCaseRequest request) {
        UpDownCase existing = require(id);
        String symbol = normalize(request.symbol());
        boolean moved = !existing.getSymbol().equals(symbol) || !existing.getCutoff().equals(request.cutoff());
        if (moved && cases.existsBySymbolAndCutoff(symbol, request.cutoff())) {
            throw new DuplicateResourceException("Ya hay un caso de " + symbol + " para el " + request.cutoff() + ".");
        }
        apply(existing, symbol, request);
        return toResponse(existing);
    }

    /** Se borra de verdad: las partidas copian el caso al empezar y no lo referencian. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void delete(Long id) {
        cases.delete(require(id));
    }

    private void apply(UpDownCase target, String symbol, UpDownCaseRequest request) {
        Series series = prices.load(symbol, request.cutoff());
        target.setSymbol(symbol);
        target.setCutoff(request.cutoff());
        target.setSector(request.sector().strip());
        target.setName(request.name().strip());
        target.setContext(request.context().strip());
        target.setLesson(request.lesson().strip());
        target.setActive(request.active());
        target.setChangePercent(BigDecimal.valueOf(series.changePercent()).setScale(2, RoundingMode.HALF_UP));
    }

    private UpDownCase require(Long id) {
        return cases.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso", id));
    }

    private static UpDownCaseResponse toResponse(UpDownCase source) {
        Double change = source.getChangePercent() == null ? null : source.getChangePercent().doubleValue();
        return new UpDownCaseResponse(source.getId(), source.getSymbol(), source.getCutoff(), source.getSector(),
                source.getName(), source.getContext(), source.getLesson(), source.isActive(), change,
                change == null ? null : UpDownChoice.of(change).name(), source.getTimesPlayed(),
                source.getLastPlayedAt());
    }

    private static String normalize(String symbol) {
        return symbol.strip().toUpperCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static List<Double> rounded(double[] values) {
        return Arrays.stream(values).map(UpDownCaseService::round).boxed().toList();
    }

}
