package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.analytics.AnalyticsAgent;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/v1")
public class OpenClawHealthController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final DocumentStorageService storage;
    private final AnalyticsAgent analytics;

    public OpenClawHealthController(JdbcTemplate jdbc,
                                    DocumentStorageService storage,
                                    AnalyticsAgent analytics) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.analytics = analytics;
    }

    @GetMapping("/health")
    public ReadinessResponse health() {
        Map<String, ComponentStatus> components = new LinkedHashMap<>();
        components.put("database", check(() -> {
            Integer result = jdbc.queryForObject("select 1", Integer.class);
            if (result == null || result != 1) throw new IllegalStateException("Database readiness query failed");
        }));
        components.put("storage", check(storage::verifyAvailable));
        components.put("analytics", check(() -> {
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            analytics.dashboard(today, today);
        }));

        boolean up = components.values().stream().allMatch(status -> "UP".equals(status.status()));
        return new ReadinessResponse(up ? "UP" : "DOWN", Instant.now(), components);
    }

    private static ComponentStatus check(CheckedRunnable check) {
        try {
            check.run();
            return new ComponentStatus("UP", null);
        } catch (Exception e) {
            return new ComponentStatus("DOWN", e.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    public record ComponentStatus(String status, String errorType) {}

    public record ReadinessResponse(
            String status,
            Instant timestamp,
            Map<String, ComponentStatus> components) {}
}
