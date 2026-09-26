package com.bombay.restaurantintelligence.web;

import com.bombay.restaurantintelligence.analytics.AnalyticsAgent;
import com.bombay.restaurantintelligence.analytics.AnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsAgent analytics;

    public AnalyticsController(AnalyticsAgent analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/dashboard")
    public AnalyticsService.DashboardData dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.dashboard(from, to);
    }

    @GetMapping("/vendors/{vendor}")
    public Map<String, Object> vendor(
            @PathVariable String vendor,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Map.of(
                "vendor", vendor,
                "from", from,
                "to", to,
                "amount", analytics.vendorSpend(vendor, from, to));
    }
}
