package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;

/**
 * Справочник облигаций, калькулятор YTM/НКД и каталог БПИФ/ETF, валют и драгметаллов
 * ({@code /api/v1/instruments/**}, {@code api/openapi.yaml}).
 */
@RestController
@RequestMapping("/api/v1/instruments")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InstrumentsController {

    private static final int MAX_TICKER_LENGTH = 32;
    private static final BigDecimal MAX_PRICE_PCT = new BigDecimal("500");
    private static final ZoneId MOEX_ZONE = ZoneId.of("Europe/Moscow");

    private final InstrumentsCatalog catalog;
    private final BondCalculator calculator;

    InstrumentsController(InstrumentsCatalog catalog, BondCalculator calculator) {
        this.catalog = catalog;
        this.calculator = calculator;
    }

    @GetMapping("/bonds")
    List<BondSpec.BondSummary> listBonds() {
        return catalog.listBonds().stream().map(BondSpec::toSummary).toList();
    }

    @GetMapping("/bonds/{ticker}/calc")
    BondCalculator.BondCalculationResponse calculateBond(
            @PathVariable String ticker,
            @RequestParam(required = false) @Nullable String pricePct,
            @RequestParam(required = false) @Nullable String asOfDate) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty() || normalized.length() > MAX_TICKER_LENGTH) {
            throw new InvalidRequestException("Invalid ticker");
        }
        BondSpec bond = catalog.findBond(normalized).orElseThrow(() -> new NotFoundException("Bond not found"));
        BigDecimal effectivePrice = parsePricePct(pricePct, bond.pricePct());
        LocalDate effectiveDate = parseAsOfDate(asOfDate);
        return calculator.calculate(bond, effectivePrice, effectiveDate).toResponse();
    }

    @GetMapping("/etfs")
    List<EtfSpec.EtfSummary> listEtfs() {
        return catalog.listEtfs().stream().map(EtfSpec::toSummary).toList();
    }

    private static BigDecimal parsePricePct(@Nullable String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid pricePct");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0 || parsed.compareTo(MAX_PRICE_PCT) > 0) {
            throw new InvalidRequestException("pricePct must be in (0, 500]");
        }
        return parsed;
    }

    private static LocalDate parseAsOfDate(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(MOEX_ZONE);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Invalid asOfDate");
        }
    }
}
