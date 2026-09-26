package com.bombay.restaurantintelligence.openclaw;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ApprovedAnalyticsAnswer(
        DashboardIntent intent,
        AnalyticsPeriod period,
        LocalDate from,
        LocalDate to,
        String metric,
        String subject,
        BigDecimal value,
        String currency,
        BigDecimal previousValue,
        BigDecimal changePercent,
        String message) {}
