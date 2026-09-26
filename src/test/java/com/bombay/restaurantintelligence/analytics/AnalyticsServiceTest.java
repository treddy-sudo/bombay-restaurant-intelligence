package com.bombay.restaurantintelligence.analytics;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.TransactionEntity;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.domain.Vendor;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {
    @Mock TransactionRepository repo;
    AnalyticsService service;

    @BeforeEach
    void setup() {
        service = new AnalyticsService(repo);
    }

    @Test
    void totalsAreDeterministicAndVerifiedOnlySource() {
        LocalDate d = LocalDate.of(2026, 9, 25);
        var sale = tx(d, TransactionType.SALE, "IN_STORE_SALES", null, "10000");
        var vendor = tx(d, TransactionType.VENDOR_PAYMENT, "VEGETABLES", "Salman", "2500");
        stubTransactions(List.of(sale, vendor));
        when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.of(sale));

        var data = service.dashboard(d, d);

        assertThat(data.selected().sales()).isEqualByComparingTo("10000.00");
        assertThat(data.selected().expenses()).isEqualByComparingTo("2500.00");
        assertThat(data.selected().netOperatingResult()).isEqualByComparingTo("7500.00");
        assertThat(data.vendorSpending().getFirst().name()).isEqualTo("Salman");
        assertThat(data.categoryExpenses().getFirst().name()).isEqualTo("VEGETABLES");
    }

    @Test
    void dateRangeAndMonthlyTotalsUseRequestedVerifiedWindow() {
        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate second = LocalDate.of(2026, 9, 25);
        var a = tx(first, TransactionType.SALE, "IN_STORE_SALES", null, "1000");
        var b = tx(second, TransactionType.SALE, "ZOMATO_SALES", null, "3000");
        stubTransactions(List.of(a, b));
        when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.of(a));

        var selected = service.dashboard(second, second);

        assertThat(selected.selected().sales()).isEqualByComparingTo("3000.00");
        assertThat(selected.monthToDate().sales()).isGreaterThanOrEqualTo(new BigDecimal("3000.00"));
    }

    @Test
    void comparisonDoesNotInventWhenPreviousMissing() {
        LocalDate d = LocalDate.of(2026, 9, 25);
        when(repo.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                eq(TransactionStatus.VERIFIED), any(), any())).thenReturn(List.of());
        when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.empty());

        assertThat(service.dashboard(d, d).comparison().message()).isEqualTo("Not enough data for comparison");
    }

    @Test
    void wtdAveragesRatiosAovWowAndMomAreCalculatedInJava() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate previousWeekDay = today.minusWeeks(1);
        LocalDate previousMonthStart = today.withDayOfMonth(1).minusMonths(1);
        int comparableDay = Math.min(today.getDayOfMonth(), previousMonthStart.lengthOfMonth());
        LocalDate previousMonthComparableDay = previousMonthStart.withDayOfMonth(comparableDay);

        var inStore = tx(today, TransactionType.SALE, "IN_STORE_SALES", null, "1000");
        var zomato = tx(today, TransactionType.SALE, "ZOMATO_SALES", null, "1000");
        var vendor = tx(today, TransactionType.VENDOR_PAYMENT, "VEGETABLES", "Salman", "300");
        var previousWeekSale = tx(previousWeekDay, TransactionType.SALE, "IN_STORE_SALES", null, "1000");
        var previousMonthSale = tx(previousMonthComparableDay, TransactionType.SALE, "IN_STORE_SALES", null, "4000");
        List<TransactionEntity> all = List.of(inStore, zomato, vendor, previousWeekSale, previousMonthSale);

        stubTransactions(all);
        when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED))
                .thenReturn(Optional.of(previousMonthSale));

        var data = service.dashboard(today, today);

        assertThat(data.selected().sales()).isEqualByComparingTo("2000.00");
        assertThat(data.selected().expenses()).isEqualByComparingTo("300.00");
        assertThat(data.selected().averageOrderValue()).isEqualByComparingTo("1000.00");
        assertThat(data.selectedPerformance().averageDailySales()).isEqualByComparingTo("2000.00");
        assertThat(data.selectedPerformance().averageDailyExpenses()).isEqualByComparingTo("300.00");
        assertThat(data.selectedPerformance().expenseToSalesRatio()).isEqualByComparingTo("15.00");
        assertThat(data.selectedPerformance().onlineSalesRatio()).isEqualByComparingTo("50.00");

        assertThat(data.weekToDate().sales()).isEqualByComparingTo("2000.00");
        assertThat(data.weekOverWeek().currentSales()).isEqualByComparingTo("2000.00");
        assertThat(data.weekOverWeek().previousSales()).isEqualByComparingTo("1000.00");
        assertThat(data.weekOverWeek().changePercent()).isEqualByComparingTo("100.00");

        assertThat(data.monthOverMonth().currentSales()).isEqualByComparingTo(data.monthToDate().sales());
        assertThat(data.monthOverMonth().previousSales()).isEqualByComparingTo("4000.00");
        BigDecimal expectedMom = data.monthToDate().sales()
                .subtract(new BigDecimal("4000.00"))
                .multiply(new BigDecimal("100"))
                .divide(new BigDecimal("4000.00"), 2, RoundingMode.HALF_UP);
        assertThat(data.monthOverMonth().changePercent()).isEqualByComparingTo(expectedMom);
        assertThat(weekStart).isNotAfter(today);
    }

    private void stubTransactions(List<TransactionEntity> all) {
        when(repo.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                eq(TransactionStatus.VERIFIED), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate from = invocation.getArgument(1);
                    LocalDate to = invocation.getArgument(2);
                    return all.stream()
                            .filter(t -> !t.getBusinessDate().isBefore(from) && !t.getBusinessDate().isAfter(to))
                            .toList();
                });
    }

    private static TransactionEntity tx(
            LocalDate date,
            TransactionType type,
            String category,
            String vendor,
            String amount) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setBusinessDate(date);
        transaction.setTransactionType(type);
        String group = category.endsWith("_SALES") ? "SALES" : "PURCHASES";
        transaction.setCategory(new Category(category, category, group));
        if (vendor != null) transaction.setVendor(new Vendor(vendor));
        transaction.setAmount(new BigDecimal(amount));
        transaction.setStatus(TransactionStatus.VERIFIED);
        transaction.setSourceType("TEST");
        return transaction;
    }
}
