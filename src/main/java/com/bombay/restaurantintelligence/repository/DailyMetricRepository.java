package com.bombay.restaurantintelligence.repository;

import com.bombay.restaurantintelligence.domain.DailyMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailyMetricRepository extends JpaRepository<DailyMetric, DailyMetric.Id> {
    List<DailyMetric> findByIdMetricDateOrderByIdMetricKey(LocalDate metricDate);
}
