package com.bombay.restaurantintelligence.whatsapp;

import com.bombay.restaurantintelligence.intake.AiExtractionProvider;
import com.bombay.restaurantintelligence.intake.CsvExtractor;
import com.bombay.restaurantintelligence.intake.ExcelExtractor;
import com.bombay.restaurantintelligence.intake.ImageExtractor;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.repository.SourceMessageRepository;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookServiceTest {
    @Mock SourceMessageRepository messages;
    @Mock SourceDocumentRepository documents;
    @Mock WhatsAppService whatsApp;
    @Mock IntakeAgent intake;
    @Mock ImageExtractor images;
    @Mock AiExtractionProvider ai;
    @Mock ExcelExtractor excel;
    @Mock CsvExtractor csv;
    @Mock DocumentStorageService storage;

    WhatsAppWebhookService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppWebhookService(
                new ObjectMapper(), messages, documents, whatsApp, intake,
                images, ai, excel, csv, storage);
    }

    @Test
    void processesWhatsAppTextThroughSharedIntakePipeline() {
        when(messages.existsBySourceMessageId("wamid.1")).thenReturn(false);
        when(intake.ingestWhatsAppText(
                "Paid Salman 4200 vegetables", "wamid.1", "919999999999"))
                .thenReturn(new NormalizationResult(
                        UUID.randomUUID(),
                        "VERIFIED",
                        "VENDOR_PAYMENT",
                        "VEGETABLES",
                        "Salman",
                        null,
                        new BigDecimal("4200.00"),
                        "INR",
                        LocalDate.of(2026, 9, 25),
                        "Recorded successfully"));

        int processed = service.process(payload("wamid.1", "Paid Salman 4200 vegetables"));

        assertThat(processed).isEqualTo(1);
        verify(messages).save(any());
        verify(intake).ingestWhatsAppText(
                "Paid Salman 4200 vegetables", "wamid.1", "919999999999");
        verify(whatsApp).sendText(eq("919999999999"), contains("Salman"));
    }

    @Test
    void duplicateWhatsAppMessageIsIgnoredIdempotently() {
        when(messages.existsBySourceMessageId("wamid.1")).thenReturn(true);

        int processed = service.process(payload("wamid.1", "Paid Salman 4200 vegetables"));

        assertThat(processed).isZero();
        verifyNoInteractions(intake);
        verify(messages, never()).save(any());
        verifyNoInteractions(whatsApp);
    }

    private static String payload(String id, String text) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"919999999999","timestamp":"1790300000","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(id, text);
    }
}
