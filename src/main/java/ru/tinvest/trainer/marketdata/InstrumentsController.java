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
 * Справочники облигаций, БПИФ/ETF, валют, драгметаллов и срочного рынка ФОРТС (фьючерсы и опционы), а также
 * серверные калькуляторы YTM/НКД, вариационной маржи/ГО и греков Блэка — Шоулза ({@code /api/v1/instruments/**},
 * {@code api/openapi.yaml}).
 */
@RestController
@RequestMapping("/api/v1/instruments")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InstrumentsController {

    private static final int MAX_TICKER_LENGTH = 32;
    private static final int MAX_ABS_CONTRACTS = 100_000;
    private static final BigDecimal MAX_PRICE_PCT = new BigDecimal("500");
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000");
    private static final BigDecimal MAX_VOLATILITY_PCT = new BigDecimal("500");
    private static final BigDecimal MAX_RISK_FREE_RATE_PCT = new BigDecimal("100");
    private static final ZoneId MOEX_ZONE = ZoneId.of("Europe/Moscow");

    private final InstrumentsCatalog catalog;
    private final BondCalculator calculator;
    private final FuturesCalculator futuresCalculator;
    private final OptionGreeksCalculator optionGreeksCalculator;

    InstrumentsController(
            InstrumentsCatalog catalog,
            BondCalculator calculator,
            FuturesCalculator futuresCalculator,
            OptionGreeksCalculator optionGreeksCalculator) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.futuresCalculator = futuresCalculator;
        this.optionGreeksCalculator = optionGreeksCalculator;
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

    @GetMapping("/futures")
    List<FuturesSpec.FuturesSummary> listFutures() {
        return catalog.listFutures().stream().map(FuturesSpec::toSummary).toList();
    }

    @GetMapping("/futures/{ticker}/calc")
    FuturesCalculator.FuturesCalculationResponse calculateFutures(
            @PathVariable String ticker,
            @RequestParam(required = false) @Nullable String entryPrice,
            @RequestParam(required = false) @Nullable String currentPrice,
            @RequestParam(required = false) @Nullable String contracts,
            @RequestParam(required = false) @Nullable String asOfDate) {
        String normalized = validateTicker(ticker);
        FuturesSpec futures =
                catalog.findFutures(normalized).orElseThrow(() -> new NotFoundException("Futures contract not found"));
        BigDecimal effectiveEntry = parsePositivePrice(entryPrice, futures.lastPrice(), "entryPrice");
        BigDecimal effectiveCurrent = parsePositivePrice(currentPrice, futures.lastPrice(), "currentPrice");
        int effectiveContracts = parseContracts(contracts);
        LocalDate effectiveDate = parseAsOfDate(asOfDate);
        return futuresCalculator
                .calculate(futures, effectiveEntry, effectiveCurrent, effectiveContracts, effectiveDate)
                .toResponse();
    }

    @GetMapping("/options")
    List<OptionSpec.OptionSummary> listOptions() {
        return catalog.listOptions().stream().map(OptionSpec::toSummary).toList();
    }

    @GetMapping("/options/{ticker}/calc")
    OptionGreeksCalculator.OptionGreeksResponse calculateOptionGreeks(
            @PathVariable String ticker,
            @RequestParam(required = false) @Nullable String spot,
            @RequestParam(required = false) @Nullable String volatilityPct,
            @RequestParam(required = false) @Nullable String riskFreeRatePct,
            @RequestParam(required = false) @Nullable String asOfDate) {
        String normalized = validateTicker(ticker);
        OptionSpec option =
                catalog.findOption(normalized).orElseThrow(() -> new NotFoundException("Option contract not found"));
        BigDecimal effectiveSpot = parsePositivePrice(spot, option.underlyingPrice(), "spot");
        BigDecimal effectiveVol = parseBoundedDecimal(
                volatilityPct, option.impliedVolPct(), BigDecimal.ZERO, false, MAX_VOLATILITY_PCT, "volatilityPct");
        BigDecimal effectiveRate = parseBoundedDecimal(
                riskFreeRatePct,
                option.riskFreeRatePct(),
                BigDecimal.ZERO,
                true,
                MAX_RISK_FREE_RATE_PCT,
                "riskFreeRatePct");
        LocalDate effectiveDate = parseAsOfDate(asOfDate);
        return optionGreeksCalculator
                .calculate(option, effectiveSpot, effectiveVol, effectiveRate, effectiveDate)
                .toResponse();
    }

    private static String validateTicker(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty() || normalized.length() > MAX_TICKER_LENGTH) {
            throw new InvalidRequestException("Invalid ticker");
        }
        return normalized;
    }

    private static BigDecimal parsePositivePrice(@Nullable String raw, BigDecimal fallback, String name) {
        return parseBoundedDecimal(raw, fallback, BigDecimal.ZERO, false, MAX_PRICE, name);
    }

    private static BigDecimal parseBoundedDecimal(
            @Nullable String raw,
            BigDecimal fallback,
            BigDecimal min,
            boolean minInclusive,
            BigDecimal max,
            String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid " + name);
        }
        boolean tooLow = minInclusive ? parsed.compareTo(min) < 0 : parsed.compareTo(min) <= 0;
        if (tooLow || parsed.compareTo(max) > 0) {
            throw new InvalidRequestException(name + " out of range");
        }
        return parsed;
    }

    private static int parseContracts(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid contracts");
        }
        if (parsed == 0 || Math.abs(parsed) > MAX_ABS_CONTRACTS) {
            throw new InvalidRequestException("contracts must be non-zero integer in [-100000, 100000]");
        }
        return parsed;
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
