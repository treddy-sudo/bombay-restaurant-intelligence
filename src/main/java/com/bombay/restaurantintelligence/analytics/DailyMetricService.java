package com.bombay.restaurantintelligence.analytics;

import com.bombay.restaurantintelligence.domain.DailyMetric;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.repository.DailyMetricRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DailyMetricService {
    private final TransactionRepository transactions;
    private final DailyMetricRepository metrics;
    private final AnalyticsService analytics;

    public DailyMetricService(TransactionRepository transactions,
                              DailyMetricRepository metrics,
                              AnalyticsService analytics) {
        this.transactions = transactions;
        this.metrics = metrics;
        this.analytics = analytics;
    }

    @Transactional
    public Map<String, BigDecimal> refresh(LocalDate date) {
        var verified = transactions.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(
                TransactionStatus.VERIFIED, date, date);
        AnalyticsService.Summary summary = analytics.summarize(verified);

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("SALES", summary.sales());
        values.put("EXPENSES", summary.expenses());
        values.put("NET_OPERATING_RESULT", summary.netOperatingResult());
        values.put("VENDOR_PAYMENTS", summary.vendorPayments());
        values.put("ONLINE_SALES", summary.onlineSales());
        values.put("OFFLINE_SALES", summary.offlineSales());
        values.put("ADVERTISING", summary.advertising());
        values.put("SALARY", summary.salary());
        values.put("AVERAGE_ORDER_VALUE", summary.averageOrderValue());
        values.put("ONLINE_ORDER_COUNT", BigDecimal.valueOf(summary.onlineOrderCount()));

        values.forEach((key, amount) -> {
            DailyMetric.Id id = new DailyMetric.Id(date, key);
            DailyMetric metric = metrics.findById(id).orElseGet(() -> new DailyMetric(date, key, amount));
            metric.setAmount(amount);
            metrics.save(metric);
        });
        return values;
    }

    @Transactional
    public void refreshRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            refresh(date);
        }
    }
}
