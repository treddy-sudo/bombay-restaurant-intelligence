package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name="app.ai.mode", havingValue="mock", matchIfMissing=true)
public class MockAiExtractionProvider implements AiExtractionProvider {
    @Override public List<IntermediateBusinessRecord> extract(byte[] content, String contentType, String filename, String sourceId, String sender) {
        return List.of(new IntermediateBusinessRecord(SourceType.IMAGE, sourceId, LocalDate.now(ZoneId.of("Asia/Kolkata")), sender,
                Map.of("description", "Mock image extraction requires owner review", "documentType", "UNKNOWN"),
                new BigDecimal("0.40"), null, filename, null, null));
    }
}
