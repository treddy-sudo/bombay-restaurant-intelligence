package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.domain.TransactionEntity;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.domain.Vendor;
import com.bombay.restaurantintelligence.repository.ReviewItemRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovedDashboardQueryServiceTest {
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final ReviewItemRepository reviews = mock(ReviewItemRepository.class);
    private final ApprovedDashboardQueryService service = new ApprovedDashboardQueryService(transactions, reviews);

    @Test
    void dateRangeSalesUsesVerifiedTransactionsAndJavaBigDecimalSum() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 2);
        when(transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, from, to))
                .thenReturn(List.of(
                        transaction(TransactionType.SALE, "100.10"),
                        transaction(TransactionType.SALE, "50.20"),
                        transaction(TransactionType.EXPENSE, "25.00")));

        ApprovedAnalyticsAnswer answer = service.query(
                DashboardIntent.DATE_RANGE_SALES,
                AnalyticsPeriod.NONE,
                from,
                to,
                null);

        assertEquals(new BigDecimal("150.30"), answer.value());
        assertEquals("sales", answer.metric());
        assertEquals("INR", answer.currency());
        assertEquals(AnalyticsPeriod.DATE_RANGE, answer.period());
        verify(transactions).findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, from, to);
    }

    @Test
    void vendorSpendRequiresAnExactNormalizedVendorName() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        TransactionEntity salman = transaction(TransactionType.VENDOR_PAYMENT, "6500.00");
        salman.setVendor(new Vendor("Salman"));
        TransactionEntity other = transaction(TransactionType.VENDOR_PAYMENT, "7000.00");
        other.setVendor(new Vendor("Salman Traders"));
        when(transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, from, to)).thenReturn(List.of(salman, other));

        ApprovedAnalyticsAnswer answer = service.query(
                DashboardIntent.VENDOR_SPEND,
                AnalyticsPeriod.DATE_RANGE,
                from,
                to,
                "  SALMAN ");

        assertEquals(new BigDecimal("6500.00"), answer.value());
        assertEquals("SALMAN", answer.subject());
    }

    @Test
    void pendingReviewCountUsesExactOpenCount() {
        when(reviews.countByStatus("OPEN")).thenReturn(7L);

        ApprovedAnalyticsAnswer answer = service.query(
                DashboardIntent.PENDING_REVIEW_COUNT,
                AnalyticsPeriod.NONE,
                null,
                null,
                null);

        assertEquals(BigDecimal.valueOf(7), answer.value());
        assertEquals("pendingReviewCount", answer.metric());
        assertNull(answer.currency());
        verify(reviews).countByStatus("OPEN");
    }

    @Test
    void expenseComparisonUsesTheImmediatelyPrecedingEqualLengthWindow() {
        LocalDate from = LocalDate.of(2026, 9, 10);
        LocalDate to = LocalDate.of(2026, 9, 11);
        LocalDate previousFrom = LocalDate.of(2026, 9, 8);
        LocalDate previousTo = LocalDate.of(2026, 9, 9);
        when(transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, from, to))
                .thenReturn(List.of(transaction(TransactionType.EXPENSE, "80.00")));
        when(transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, previousFrom, previousTo))
                .thenReturn(List.of(transaction(TransactionType.EXPENSE, "40.00")));

        ApprovedAnalyticsAnswer answer = service.query(
                DashboardIntent.EXPENSE_COMPARISON,
                AnalyticsPeriod.DATE_RANGE,
                from,
                to,
                null);

        assertEquals(new BigDecimal("80.00"), answer.value());
        assertEquals(new BigDecimal("40.00"), answer.previousValue());
        assertEquals(new BigDecimal("100.00"), answer.changePercent());
    }

    private static TransactionEntity transaction(TransactionType type, String amount) {
        TransactionEntity tx = new TransactionEntity();
        tx.setBusinessDate(LocalDate.of(2026, 9, 1));
        tx.setTransactionType(type);
        tx.setAmount(new BigDecimal(amount));
        tx.setStatus(TransactionStatus.VERIFIED);
        tx.setSourceType("TEST");
        return tx;
    }
}
