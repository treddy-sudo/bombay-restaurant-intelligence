package com.bombay.restaurantintelligence.analytics;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.TransactionEntity;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private final TransactionRepository transactions;

    public AnalyticsService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public DashboardData dashboard(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (from == null) from = today;
        if (to == null) to = today;
        if (to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");

        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate monthStart = today.withDayOfMonth(1);

        Summary todaySummary = summarize(load(today, today));
        Summary weekToDate = summarize(load(weekStart, today));
        Summary monthToDate = summarize(load(monthStart, today));

        List<TransactionEntity> selected = load(from, to);
        Summary selectedSummary = summarize(selected);
        Performance selectedPerformance = performance(selectedSummary, from, to);
        List<DailyPoint> daily = daily(selected, from, to);
        List<NamedAmount> category = group(selected,
                t -> isExpense(t) && t.getCategory() != null,
                t -> t.getCategory().getCode());
        List<NamedAmount> vendor = group(selected,
                t -> isExpense(t) && t.getVendor() != null,
                t -> t.getVendor().getName());
        Map<String, BigDecimal> channel = channelSplit(selected);
        Map<String, BigDecimal> aggregators = aggregatorSplit(selected);

        Comparison comparison = comparison(from, to, selectedSummary.sales());
        Comparison weekOverWeek = weekOverWeek(weekStart, today, weekToDate.sales());
        Comparison monthOverMonth = monthOverMonth(monthStart, today, monthToDate.sales());

        String first = transactions.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)
                .map(t -> t.getBusinessDate().toString())
                .orElse(null);

        return new DashboardData(
                today,
                from,
                to,
                first,
                todaySummary,
                weekToDate,
                monthToDate,
                selectedSummary,
                selectedPerformance,
                daily,
                category,
                vendor,
                channel,
                aggregators,
                comparison,
                weekOverWeek,
                monthOverMonth);
    }

    private List<TransactionEntity> load(LocalDate from, LocalDate to) {
        return transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, from, to);
    }

    public Summary summarize(List<TransactionEntity> txs) {
        BigDecimal sales = sum(txs, this::isSale);
        BigDecimal expenses = sum(txs, this::isExpense);
        BigDecimal vendor = sum(txs, t -> t.getTransactionType() == TransactionType.VENDOR_PAYMENT);
        BigDecimal online = sum(txs, t -> isCategory(t, "ONLINE_SALES", "ZOMATO_SALES", "SWIGGY_SALES"));
        BigDecimal offline = sum(txs, t -> isCategory(t, "IN_STORE_SALES", "CASH_SALES", "UPI_SALES"));
        BigDecimal advertising = sum(txs, t -> t.getTransactionType() == TransactionType.ADVERTISING);
        BigDecimal salary = sum(txs, t -> t.getTransactionType() == TransactionType.SALARY);
        int orderCount = (int) txs.stream()
                .filter(t -> isCategory(t, "ZOMATO_SALES", "SWIGGY_SALES", "ONLINE_SALES"))
                .count();
        BigDecimal aov = orderCount == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : online.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);
        return new Summary(
                sales,
                expenses,
                sales.subtract(expenses),
                vendor,
                online,
                offline,
                advertising,
                salary,
                aov,
                orderCount);
    }

    public Performance performance(Summary summary, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal divisor = BigDecimal.valueOf(days);
        BigDecimal averageDailySales = summary.sales().divide(divisor, 2, RoundingMode.HALF_UP);
        BigDecimal averageDailyExpenses = summary.expenses().divide(divisor, 2, RoundingMode.HALF_UP);
        return new Performance(
                averageDailySales,
                averageDailyExpenses,
                percentage(summary.expenses(), summary.sales()),
                percentage(summary.onlineSales(), summary.sales()));
    }

    private Comparison comparison(LocalDate from, LocalDate to, BigDecimal current) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        return compareAgainst(current, previousFrom, previousTo);
    }

    private Comparison weekOverWeek(LocalDate weekStart, LocalDate today, BigDecimal current) {
        long days = ChronoUnit.DAYS.between(weekStart, today) + 1;
        LocalDate previousFrom = weekStart.minusWeeks(1);
        LocalDate previousTo = previousFrom.plusDays(days - 1);
        return compareAgainst(current, previousFrom, previousTo);
    }

    private Comparison monthOverMonth(LocalDate monthStart, LocalDate today, BigDecimal current) {
        LocalDate previousFrom = monthStart.minusMonths(1);
        int comparableDay = Math.min(today.getDayOfMonth(), previousFrom.lengthOfMonth());
        LocalDate previousTo = previousFrom.withDayOfMonth(comparableDay);
        return compareAgainst(current, previousFrom, previousTo);
    }

    private Comparison compareAgainst(BigDecimal current, LocalDate previousFrom, LocalDate previousTo) {
        List<TransactionEntity> previousTransactions = load(previousFrom, previousTo);
        if (previousTransactions.isEmpty()) {
            return new Comparison(null, null, null, "Not enough data for comparison");
        }
        BigDecimal previous = summarize(previousTransactions).sales();
        if (previous.signum() == 0) {
            return new Comparison(current, previous, null, "Not enough data for percentage comparison");
        }
        BigDecimal percent = current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
        return new Comparison(current, previous, percent, null);
    }

    private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return null;
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private List<DailyPoint> daily(List<TransactionEntity> txs, LocalDate from, LocalDate to) {
        Map<LocalDate, List<TransactionEntity>> byDate = txs.stream()
                .collect(Collectors.groupingBy(TransactionEntity::getBusinessDate));
        List<DailyPoint> out = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<TransactionEntity> day = byDate.getOrDefault(date, List.of());
            out.add(new DailyPoint(date, sum(day, this::isSale), sum(day, this::isExpense)));
        }
        return out;
    }

    private List<NamedAmount> group(
            List<TransactionEntity> txs,
            Predicate<TransactionEntity> include,
            java.util.function.Function<TransactionEntity, String> key) {
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (TransactionEntity transaction : txs) {
            if (include.test(transaction)) {
                amounts.merge(key.apply(transaction), transaction.getAmount(), BigDecimal::add);
            }
        }
        return amounts.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(entry -> new NamedAmount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Map<String, BigDecimal> channelSplit(List<TransactionEntity> txs) {
        Map<String, BigDecimal> split = new LinkedHashMap<>();
        split.put("ONLINE", sum(txs, t -> isCategory(t, "ONLINE_SALES", "ZOMATO_SALES", "SWIGGY_SALES")));
        split.put("OFFLINE", sum(txs, t -> isCategory(t, "IN_STORE_SALES", "CASH_SALES", "UPI_SALES")));
        return split;
    }

    private Map<String, BigDecimal> aggregatorSplit(List<TransactionEntity> txs) {
        Map<String, BigDecimal> split = new LinkedHashMap<>();
        split.put("ZOMATO", sum(txs, t -> isCategory(t, "ZOMATO_SALES")));
        split.put("SWIGGY", sum(txs, t -> isCategory(t, "SWIGGY_SALES")));
        return split;
    }

    private BigDecimal sum(List<TransactionEntity> txs, Predicate<TransactionEntity> predicate) {
        return txs.stream()
                .filter(predicate)
                .map(TransactionEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isSale(TransactionEntity transaction) {
        return transaction.getTransactionType() == TransactionType.SALE;
    }

    private boolean isExpense(TransactionEntity transaction) {
        return switch (transaction.getTransactionType()) {
            case SALE, SETTLEMENT, REFUND -> false;
            default -> true;
        };
    }

    private static boolean isCategory(TransactionEntity transaction, String... codes) {
        Category category = transaction.getCategory();
        if (category == null) return false;
        for (String code : codes) {
            if (code.equals(category.getCode())) return true;
        }
        return false;
    }

    public record Summary(
            BigDecimal sales,
            BigDecimal expenses,
            BigDecimal netOperatingResult,
            BigDecimal vendorPayments,
            BigDecimal onlineSales,
            BigDecimal offlineSales,
            BigDecimal advertising,
            BigDecimal salary,
            BigDecimal averageOrderValue,
            int onlineOrderCount) {}

    public record Performance(
            BigDecimal averageDailySales,
            BigDecimal averageDailyExpenses,
            BigDecimal expenseToSalesRatio,
            BigDecimal onlineSalesRatio) {}

    public record DailyPoint(LocalDate date, BigDecimal sales, BigDecimal expenses) {}

    public record NamedAmount(String name, BigDecimal amount) {}

    public record Comparison(
            BigDecimal currentSales,
            BigDecimal previousSales,
            BigDecimal changePercent,
            String message) {}

    public record DashboardData(
            LocalDate today,
            LocalDate from,
            LocalDate to,
            String dataAvailableFrom,
            Summary todaySummary,
            Summary weekToDate,
            Summary monthToDate,
            Summary selected,
            Performance selectedPerformance,
            List<DailyPoint> daily,
            List<NamedAmount> categoryExpenses,
            List<NamedAmount> vendorSpending,
            Map<String, BigDecimal> onlineVsOffline,
            Map<String, BigDecimal> zomatoVsSwiggy,
            Comparison comparison,
            Comparison weekOverWeek,
            Comparison monthOverMonth) {}
}
