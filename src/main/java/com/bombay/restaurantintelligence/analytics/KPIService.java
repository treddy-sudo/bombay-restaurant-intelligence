package com.bombay.restaurantintelligence.analytics;
import org.springframework.stereotype.Service;
@Service public class KPIService {private final AnalyticsService analytics;public KPIService(AnalyticsService analytics){this.analytics=analytics;}public AnalyticsService.DashboardData dashboard(java.time.LocalDate from,java.time.LocalDate to){return analytics.dashboard(from,to);}}
