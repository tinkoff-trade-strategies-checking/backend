package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;

/**
 * Справочники облигаций, БПИФ/ETF, валют, драгметаллов, срочного рынка ФОРТС и дивидендного календаря MOEX, а также
 * серверные калькуляторы YTM/НКД, вариационной маржи/ГО, греков Блэка — Шоулза, дивидендов T+1, НДФЛ/ИИС и DCA
 * (TWR/XIRR) ({@code /api/v1/instruments/**}, {@code api/openapi.yaml}).
 */
@RestController
@RequestMapping("/api/v1/instruments")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InstrumentsController {

    private static final int MAX_TICKER_LENGTH = 32;
    private static final int MAX_ABS_CONTRACTS = 100_000;
    private static final int MAX_SHARES = 10_000_000;
    private static final int MAX_DCA_CASHFLOWS = 120;
    private static final BigDecimal MAX_PRICE_PCT = new BigDecimal("500");
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000");
    private static final BigDecimal MAX_MONETARY = new BigDecimal("1000000000000");
    private static final BigDecimal MIN_MONETARY = MAX_MONETARY.negate();
    private static final BigDecimal MAX_VOLATILITY_PCT = new BigDecimal("500");
    private static final BigDecimal MAX_RISK_FREE_RATE_PCT = new BigDecimal("100");
    private static final ZoneId MOEX_ZONE = ZoneId.of("Europe/Moscow");

    private final InstrumentsCatalog catalog;
    private final BondCalculator calculator;
    private final FuturesCalculator futuresCalculator;
    private final OptionGreeksCalculator optionGreeksCalculator;
    private final DividendCalculator dividendCalculator;
    private final TaxCalculator taxCalculator;
    private final DcaCalculator dcaCalculator;

    InstrumentsController(
            InstrumentsCatalog catalog,
            BondCalculator calculator,
            FuturesCalculator futuresCalculator,
            OptionGreeksCalculator optionGreeksCalculator,
            DividendCalculator dividendCalculator,
            TaxCalculator taxCalculator,
            DcaCalculator dcaCalculator) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.futuresCalculator = futuresCalculator;
        this.optionGreeksCalculator = optionGreeksCalculator;
        this.dividendCalculator = dividendCalculator;
        this.taxCalculator = taxCalculator;
        this.dcaCalculator = dcaCalculator;
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

    @GetMapping("/dividends")
    List<DividendEventSpec.DividendEventSummary> listDividends(
            @RequestParam(required = false) @Nullable String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return catalog.listDividends().stream()
                    .map(DividendEventSpec::toSummary)
                    .toList();
        }
        String normalized = validateTicker(ticker);
        return catalog.listDividendsByTicker(normalized).stream()
                .map(DividendEventSpec::toSummary)
                .toList();
    }

    @GetMapping("/dividends/{ticker}/calc")
    DividendCalculator.DividendPayoutResponse calculateDividendPayout(
            @PathVariable String ticker,
            @RequestParam(required = false) @Nullable String shares,
            @RequestParam(required = false) @Nullable String taxRatePct) {
        String normalized = validateTicker(ticker);
        DividendEventSpec event = catalog.findLatestDividend(normalized)
                .orElseThrow(() -> new NotFoundException("Dividend event not found for ticker"));
        int effectiveShares = parseShares(shares);
        int effectiveTaxRate = parseTaxRatePct(taxRatePct);
        return dividendCalculator
                .calculate(event, effectiveShares, effectiveTaxRate)
                .toResponse();
    }

    @PostMapping("/tax/calc")
    TaxCalculator.TaxCalculationResponse calculateTax(@RequestBody TaxCalcRequestBody body) {
        if (body == null) {
            throw new InvalidRequestException("Request body is required");
        }
        BigDecimal grossProfit = parseRequiredMoney(body.grossProfitRub(), "grossProfitRub", true);
        BigDecimal priorLosses = parseOptionalNonNegativeMoney(body.priorLossesRub(), "priorLossesRub");
        BigDecimal dividends = parseOptionalNonNegativeMoney(body.dividendsRub(), "dividendsRub");
        BigDecimal annualContributions =
                parseOptionalNonNegativeMoney(body.annualContributionsRub(), "annualContributionsRub");
        TaxCalculator.AccountType accountType = parseAccountType(body.accountType());
        return taxCalculator
                .calculate(grossProfit, priorLosses, dividends, accountType, annualContributions)
                .toResponse();
    }

    @PostMapping("/dca/calc")
    DcaCalculator.DcaCalculationResponse calculateDca(@RequestBody DcaCalcRequestBody body) {
        if (body == null) {
            throw new InvalidRequestException("Request body is required");
        }
        if (body.cashflows() == null
                || body.cashflows().isEmpty()
                || body.cashflows().size() > MAX_DCA_CASHFLOWS) {
            throw new InvalidRequestException("cashflows must contain 1..120 items");
        }
        BigDecimal finalValue = parseRequiredMoney(body.finalValueRub(), "finalValueRub", false);
        LocalDate finalDate = parseRequiredDate(body.finalDate(), "finalDate");

        List<DcaCalculator.Cashflow> parsed = new ArrayList<>(body.cashflows().size());
        for (int i = 0; i < body.cashflows().size(); i++) {
            DcaCashflowBody item = body.cashflows().get(i);
            if (item == null) {
                throw new InvalidRequestException("cashflows[" + i + "] must not be null");
            }
            LocalDate cfDate = parseRequiredDate(item.date(), "cashflows[" + i + "].date");
            if (cfDate.isAfter(finalDate)) {
                throw new InvalidRequestException("cashflows[" + i + "].date must not be after finalDate");
            }
            BigDecimal amount = parseRequiredMoney(item.amountRub(), "cashflows[" + i + "].amountRub", true);
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                throw new InvalidRequestException("cashflows[" + i + "].amountRub must be non-zero");
            }
            BigDecimal valBefore = null;
            if (item.portfolioValueBeforeRub() != null
                    && !item.portfolioValueBeforeRub().isBlank()) {
                valBefore = parseRequiredMoney(
                        item.portfolioValueBeforeRub(), "cashflows[" + i + "].portfolioValueBeforeRub", false);
            }
            parsed.add(new DcaCalculator.Cashflow(cfDate, amount, valBefore));
        }

        return dcaCalculator.calculate(parsed, finalValue, finalDate).toResponse();
    }

    record TaxCalcRequestBody(
            @Nullable String grossProfitRub,
            @Nullable String priorLossesRub,
            @Nullable String dividendsRub,
            @Nullable String accountType,
            @Nullable String annualContributionsRub) {}

    record DcaCashflowBody(
            @Nullable String date,
            @Nullable String amountRub,
            @Nullable String portfolioValueBeforeRub) {}

    record DcaCalcRequestBody(
            @Nullable List<@Nullable DcaCashflowBody> cashflows,
            @Nullable String finalValueRub,
            @Nullable String finalDate) {}

    private static String validateTicker(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty() || normalized.length() > MAX_TICKER_LENGTH) {
            throw new InvalidRequestException("Invalid ticker");
        }
        return normalized;
    }

    private static int parseShares(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 100;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid shares");
        }
        if (parsed < 1 || parsed > MAX_SHARES) {
            throw new InvalidRequestException("shares must be in [1, 10000000]");
        }
        return parsed;
    }

    private static int parseTaxRatePct(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 13;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("taxRatePct must be 13 or 15");
        }
        if (parsed != 13 && parsed != 15) {
            throw new InvalidRequestException("taxRatePct must be 13 or 15");
        }
        return parsed;
    }

    private static TaxCalculator.AccountType parseAccountType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("accountType is required");
        }
        try {
            return TaxCalculator.AccountType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("accountType must be BROKERAGE, IIS_A, IIS_B or IIS_3");
        }
    }

    private static BigDecimal parseRequiredMoney(@Nullable String raw, String name, boolean allowNegative) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(name + " is required");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid " + name);
        }
        BigDecimal min = allowNegative ? MIN_MONETARY : BigDecimal.ZERO;
        if (parsed.compareTo(min) < 0 || parsed.compareTo(MAX_MONETARY) > 0) {
            throw new InvalidRequestException(name + " out of range");
        }
        return parsed;
    }

    private static BigDecimal parseOptionalNonNegativeMoney(@Nullable String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        return parseRequiredMoney(raw, name, false);
    }

    private static LocalDate parseRequiredDate(@Nullable String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(name + " is required");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Invalid " + name);
        }
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
