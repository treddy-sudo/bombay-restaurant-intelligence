package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record IntermediateBusinessRecord(
        SourceType sourceType,
        String sourceId,
        LocalDate businessDate,
        String sender,
        Map<String, String> fields,
        BigDecimal confidence,
        String rawText,
        String sourceFilename,
        String originalFileLocation,
        String fileChecksum) {
    public IntermediateBusinessRecord {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        confidence = confidence == null ? BigDecimal.ZERO : confidence;
    }
}
