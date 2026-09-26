package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.domain.TransactionEntity;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.repository.ReviewItemRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

@Service
public class ApprovedDashboardQueryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final long MAX_RANGE_DAYS = 366;

    private final TransactionRepository transactions;
    private final ReviewItemRepository reviews;

    public ApprovedDashboardQueryService(TransactionRepository transactions, ReviewItemRepository reviews) {
        this.transactions = transactions;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public ApprovedAnalyticsAnswer query(
            DashboardIntent intent,
            AnalyticsPeriod period,
            LocalDate from,
            LocalDate to,
            String subject) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        if (intent == DashboardIntent.PENDING_REVIEW_COUNT) {
            return new ApprovedAnalyticsAnswer(
                    intent,
                    AnalyticsPeriod.NONE,
                    null,
                    null,
                    "pendingReviewCount",
                    null,
                    BigDecimal.valueOf(reviews.countByStatus("OPEN")),
                    null,
                    null,
                    null,
                    null);
        }

        Range range = resolveRange(intent, period, from, to);
        return switch (intent) {
            case TODAY_SALES, YESTERDAY_SALES, DATE_RANGE_SALES, THIS_WEEK_SALES, THIS_MONTH_SALES ->
                    amount(intent, range, "sales", null, sum(load(range), this::isSale));
            case TODAY_EXPENSES, YESTERDAY_EXPENSES, DATE_RANGE_EXPENSES, THIS_WEEK_EXPENSES, THIS_MONTH_EXPENSES ->
                    amount(intent, range, "expenses", null, sum(load(range), this::isExpense));
            case TODAY_PROFIT -> {
                List<TransactionEntity> txs = load(range);
                BigDecimal sales = sum(txs, this::isSale);
                BigDecimal expenses = sum(txs, this::isExpense);
                yield amount(intent, range, "netOperatingResult", null, sales.subtract(expenses).setScale(2, RoundingMode.HALF_UP));
            }
            case VENDOR_SPEND -> {
                String requiredSubject = requireSubject(subject, "vendor");
                yield amount(intent, range, "vendorSpend", requiredSubject,
                        sum(load(range), tx -> isExpense(tx)
                                && tx.getVendor() != null
                                && normalizedName(tx.getVendor().getName()).equals(normalizedName(requiredSubject))));
            }
            case CATEGORY_SPEND -> {
                String requiredSubject = requireSubject(subject, "category");
                yield amount(intent, range, "categorySpend", requiredSubject,
                        sum(load(range), tx -> isExpense(tx)
                                && tx.getCategory() != null
                                && (normalizedKey(tx.getCategory().getCode()).equals(normalizedKey(requiredSubject))
                                    || normalizedKey(tx.getCategory().getName()).equals(normalizedKey(requiredSubject)))));
            }
            case SALARY_TOTAL -> amount(intent, range, "salary", null,
                    sum(load(range), tx -> tx.getTransactionType() == TransactionType.SALARY));
            case EMPLOYEE_SALARY -> {
                String requiredSubject = requireSubject(subject, "employee");
                yield amount(intent, range, "employeeSalary", requiredSubject,
                        sum(load(range), tx -> tx.getTransactionType() == TransactionType.SALARY
                                && tx.getEmployee() != null
                                && normalizedName(tx.getEmployee().getName()).equals(normalizedName(requiredSubject))));
            }
            case CASH_SALES -> amount(intent, range, "cashSales", null,
                    sum(load(range), tx -> isSale(tx) && hasCategory(tx, "CASH_SALES")));
            case UPI_SALES -> amount(intent, range, "upiSales", null,
                    sum(load(range), tx -> isSale(tx) && hasCategory(tx, "UPI_SALES")));
            case ZOMATO_SALES -> amount(intent, range, "zomatoSales", null,
                    sum(load(range), tx -> isSale(tx) && hasCategory(tx, "ZOMATO_SALES")));
            case SWIGGY_SALES -> amount(intent, range, "swiggySales", null,
                    sum(load(range), tx -> isSale(tx) && hasCategory(tx, "SWIGGY_SALES")));
            case SALES_COMPARISON -> comparison(intent, range, "sales", this::isSale);
            case EXPENSE_COMPARISON -> comparison(intent, range, "expenses", this::isExpense);
            case PENDING_REVIEW_COUNT -> throw new IllegalStateException("handled before range resolution");
        };
    }

    private ApprovedAnalyticsAnswer comparison(
            DashboardIntent intent,
            Range currentRange,
            String metric,
            Predicate<TransactionEntity> predicate) {
        List<TransactionEntity> currentTransactions = load(currentRange);
        BigDecimal current = sum(currentTransactions, predicate);

        long days = ChronoUnit.DAYS.between(currentRange.from(), currentRange.to()) + 1;
        Range previousRange = new Range(
                currentRange.from().minusDays(days),
                currentRange.from().minusDays(1),
                currentRange.period());
        List<TransactionEntity> previousTransactions = load(previousRange);
        if (previousTransactions.isEmpty()) {
            return new ApprovedAnalyticsAnswer(
                    intent,
                    currentRange.period(),
                    currentRange.from(),
                    currentRange.to(),
                    metric,
                    null,
                    current,
                    "INR",
                    null,
                    null,
                    "Not enough verified data for comparison");
        }

        BigDecimal previous = sum(previousTransactions, predicate);
        if (previous.signum() == 0) {
            return new ApprovedAnalyticsAnswer(
                    intent,
                    currentRange.period(),
                    currentRange.from(),
                    currentRange.to(),
                    metric,
                    null,
                    current,
                    "INR",
                    previous,
                    null,
                    "Not enough verified data for percentage comparison");
        }

        BigDecimal changePercent = current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
        return new ApprovedAnalyticsAnswer(
                intent,
                currentRange.period(),
                currentRange.from(),
                currentRange.to(),
                metric,
                null,
                current,
                "INR",
                previous,
                changePercent,
                null);
    }

    private ApprovedAnalyticsAnswer amount(
            DashboardIntent intent,
            Range range,
            String metric,
            String subject,
            BigDecimal value) {
        return new ApprovedAnalyticsAnswer(
                intent,
                range.period(),
                range.from(),
                range.to(),
                metric,
                subject,
                value,
                "INR",
                null,
                null,
                null);
    }

    private Range resolveRange(DashboardIntent intent, AnalyticsPeriod requested, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return switch (intent) {
            case TODAY_SALES, TODAY_EXPENSES, TODAY_PROFIT -> new Range(today, today, AnalyticsPeriod.TODAY);
            case YESTERDAY_SALES, YESTERDAY_EXPENSES -> {
                LocalDate yesterday = today.minusDays(1);
                yield new Range(yesterday, yesterday, AnalyticsPeriod.YESTERDAY);
            }
            case THIS_WEEK_SALES, THIS_WEEK_EXPENSES -> new Range(today.with(DayOfWeek.MONDAY), today, AnalyticsPeriod.THIS_WEEK);
            case THIS_MONTH_SALES, THIS_MONTH_EXPENSES -> new Range(today.withDayOfMonth(1), today, AnalyticsPeriod.THIS_MONTH);
            case DATE_RANGE_SALES, DATE_RANGE_EXPENSES -> validatedRange(from, to, AnalyticsPeriod.DATE_RANGE);
            default -> resolveRequestedPeriod(requested, from, to, today);
        };
    }

    private Range resolveRequestedPeriod(AnalyticsPeriod requested, LocalDate from, LocalDate to, LocalDate today) {
        AnalyticsPeriod period = requested == null || requested == AnalyticsPeriod.NONE ? AnalyticsPeriod.TODAY : requested;
        return switch (period) {
            case NONE -> throw new IllegalStateException("NONE is normalized before switch");
            case TODAY -> new Range(today, today, AnalyticsPeriod.TODAY);
            case YESTERDAY -> {
                LocalDate yesterday = today.minusDays(1);
                yield new Range(yesterday, yesterday, AnalyticsPeriod.YESTERDAY);
            }
            case THIS_WEEK -> new Range(today.with(DayOfWeek.MONDAY), today, AnalyticsPeriod.THIS_WEEK);
            case THIS_MONTH -> new Range(today.withDayOfMonth(1), today, AnalyticsPeriod.THIS_MONTH);
            case DATE_RANGE -> validatedRange(from, to, AnalyticsPeriod.DATE_RANGE);
        };
    }

    private Range validatedRange(LocalDate from, LocalDate to, AnalyticsPeriod period) {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required for DATE_RANGE");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) throw new IllegalArgumentException("analytics date range cannot exceed 366 days");
        return new Range(from, to, period);
    }

    private List<TransactionEntity> load(Range range) {
        return transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED,
                range.from(),
                range.to());
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

    private boolean hasCategory(TransactionEntity transaction, String code) {
        return transaction.getCategory() != null && code.equalsIgnoreCase(transaction.getCategory().getCode());
    }

    private static String requireSubject(String value, String kind) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(kind + " subject is required");
        String trimmed = value.trim();
        if (trimmed.length() > 120) throw new IllegalArgumentException(kind + " subject is too long");
        return trimmed;
    }

    private static String normalizedName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String normalizedKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record Range(LocalDate from, LocalDate to, AnalyticsPeriod period) {}
}
