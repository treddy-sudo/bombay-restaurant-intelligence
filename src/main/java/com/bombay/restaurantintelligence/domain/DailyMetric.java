package com.bombay.restaurantintelligence.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "daily_metrics")
public class DailyMetric {
    @EmbeddedId
    private Id id;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DailyMetric() {}

    public DailyMetric(LocalDate metricDate, String metricKey, BigDecimal amount) {
        this.id = new Id(metricDate, metricKey);
        setAmount(amount);
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        this.updatedAt = Instant.now();
    }

    public LocalDate getMetricDate() { return id.metricDate; }
    public String getMetricKey() { return id.metricKey; }
    public BigDecimal getAmount() { return amount; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "metric_date", nullable = false)
        private LocalDate metricDate;

        @Column(name = "metric_key", nullable = false, length = 120)
        private String metricKey;

        protected Id() {}

        public Id(LocalDate metricDate, String metricKey) {
            this.metricDate = metricDate;
            this.metricKey = metricKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Id id)) return false;
            return Objects.equals(metricDate, id.metricDate) && Objects.equals(metricKey, id.metricKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(metricDate, metricKey);
        }
    }
}
