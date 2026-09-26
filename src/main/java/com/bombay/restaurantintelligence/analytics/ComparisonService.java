package com.bombay.restaurantintelligence.analytics;
import org.springframework.stereotype.Service;
@Service public class ComparisonService {private final AnalyticsService analytics;public ComparisonService(AnalyticsService analytics){this.analytics=analytics;}public AnalyticsService.Comparison compare(java.time.LocalDate from,java.time.LocalDate to){return analytics.dashboard(from,to).comparison();}}
