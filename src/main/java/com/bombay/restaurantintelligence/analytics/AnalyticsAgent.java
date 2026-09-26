package com.bombay.restaurantintelligence.analytics;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class AnalyticsAgent {
    private final KPIService kpis;
    private final VendorAnalyticsService vendors;

    public AnalyticsAgent(KPIService kpis, VendorAnalyticsService vendors) {
        this.kpis = kpis;
        this.vendors = vendors;
    }

    public AnalyticsService.DashboardData dashboard(LocalDate from, LocalDate to) {
        return kpis.dashboard(from, to);
    }

    public BigDecimal vendorSpend(String vendor, LocalDate from, LocalDate to) {
        return vendors.vendorSpend(vendor, from, to);
    }
}
