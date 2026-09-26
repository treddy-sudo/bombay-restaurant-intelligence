package com.bombay.restaurantintelligence.normalization;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.UUID;
public record NormalizationResult(UUID transactionId,String status,String transactionType,String category,String vendor,String employee,BigDecimal amount,String currency,LocalDate businessDate,String message) {}
