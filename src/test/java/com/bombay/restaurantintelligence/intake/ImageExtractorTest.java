package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageExtractorTest {
    @Test
    void preservesCandidateFieldsButOwnsStableSourceIdentityAndTraceability() {
        AiExtractionProvider ai = mock(AiExtractionProvider.class);
        byte[] bytes = "fake-image".getBytes();
        when(ai.extract(bytes, "image/jpeg", "receipt.jpg", "wamid.1", "manager"))
                .thenReturn(List.of(new IntermediateBusinessRecord(
                        SourceType.IMAGE,
                        "model-supplied-id",
                        LocalDate.of(2026, 9, 26),
                        "model-supplied-sender",
                        Map.of(
                                "transactionType", "VENDOR_PAYMENT",
                                "category", "VEGETABLES",
                                "vendor", "Salman",
                                "amount", "6700.00"),
                        new BigDecimal("0.93"),
                        "Tomatoes 4500, onions 2200",
                        null,
                        null,
                        null)));

        var extractor = new ImageExtractor(ai);
        var records = extractor.extract(
                bytes,
                "image/jpeg",
                "receipt.jpg",
                "wamid.1",
                "manager",
                SourceType.WHATSAPP_IMAGE,
                "local://receipt.jpg",
                "abc123");

        assertThat(records).hasSize(1);
        var record = records.getFirst();
        assertThat(record.sourceType()).isEqualTo(SourceType.WHATSAPP_IMAGE);
        assertThat(record.sourceId()).isEqualTo("wamid.1:1");
        assertThat(record.sender()).isEqualTo("manager");
        assertThat(record.fields().get("amount")).isEqualTo("6700.00");
        assertThat(record.confidence()).isEqualByComparingTo("0.93");
        assertThat(record.sourceFilename()).isEqualTo("receipt.jpg");
        assertThat(record.originalFileLocation()).isEqualTo("local://receipt.jpg");
        assertThat(record.fileChecksum()).isEqualTo("abc123");
    }
}
