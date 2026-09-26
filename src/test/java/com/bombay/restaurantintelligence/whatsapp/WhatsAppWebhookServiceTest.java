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
import com.fasterxml.jackson.databind.JsonNode;
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
    private final ObjectMapper mapper = new ObjectMapper();

    @Mock SourceMessageRepository messages;
    @Mock SourceDocumentRepository documents;
    @Mock WhatsAppService whatsApp;
    @Mock IntakeAgent intake;
    @Mock ImageExtractor images;
    @Mock AiExtractionProvider ai;
    @Mock ExcelExtractor excel;
    @Mock CsvExtractor csv;
    @Mock DocumentStorageService storage;
    @Mock OpenClawWhatsAppGatewayClient openClaw;

    WhatsAppWebhookService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppWebhookService(
                mapper, messages, documents, whatsApp, intake,
                images, ai, excel, csv, storage, openClaw);
    }

    @Test
    void legacyModeStillProcessesWhatsAppTextThroughSharedIntakePipeline() {
        when(openClaw.enabled()).thenReturn(false);
        when(messages.existsBySourceMessageId("wamid.1")).thenReturn(false);
        when(intake.ingestWhatsAppText(
                "Paid Salman 4200 vegetables", "wamid.1", "919999999999"))
                .thenReturn(normalization("VERIFIED", "4200.00"));

        int processed = service.process(textPayload("wamid.1", "Paid Salman 4200 vegetables"));

        assertThat(processed).isEqualTo(1);
        verify(messages).save(any());
        verify(openClaw).enabled();
        verify(intake).ingestWhatsAppText(
                "Paid Salman 4200 vegetables", "wamid.1", "919999999999");
        verify(whatsApp).sendText(eq("919999999999"), contains("Salman"));
    }

    @Test
    void openClawTextDataUsesRestaurantRouteToolAndSpringResultForAck() throws Exception {
        when(openClaw.enabled()).thenReturn(true);
        when(messages.existsBySourceMessageId("wamid.oc.text")).thenReturn(false);
        when(openClaw.routeText(
                "Paid Salman 4200 vegetables", "wamid.oc.text", "919999999999"))
                .thenReturn(json("""
                        {"ok":true,"result":{"classification":"DATA_TEXT","silent":true,
                        "record":{"status":"VERIFIED","transactionType":"VENDOR_PAYMENT","category":"VEGETABLES","vendor":"Salman","employee":null,"amount":4200.00}}}
                        """));

        int processed = service.process(textPayload("wamid.oc.text", "Paid Salman 4200 vegetables"));

        assertThat(processed).isEqualTo(1);
        verify(openClaw).routeText(
                "Paid Salman 4200 vegetables", "wamid.oc.text", "919999999999");
        verifyNoInteractions(intake);
        verify(whatsApp).sendText(eq("919999999999"), contains("₹4200 vegetables to Salman"));
    }

    @Test
    void openClawDashboardQuestionReturnsToolReplyToWhatsApp() throws Exception {
        when(openClaw.enabled()).thenReturn(true);
        when(messages.existsBySourceMessageId("wamid.oc.question")).thenReturn(false);
        when(openClaw.routeText(
                "What are today's sales?", "wamid.oc.question", "919999999999"))
                .thenReturn(json("""
                        {"ok":true,"result":{"classification":"DASHBOARD_QUESTION","intent":"TODAY_SALES",
                        "silent":false,"reply":"Verified sales today are ₹12,000."}}
                        """));

        int processed = service.process(textPayload("wamid.oc.question", "What are today's sales?"));

        assertThat(processed).isEqualTo(1);
        verify(whatsApp).sendText("919999999999", "Verified sales today are ₹12,000.");
    }

    @Test
    void openClawImageDownloadsMetaMediaThenUsesVisionTool() throws Exception {
        byte[] bytes = "receipt-image".getBytes();
        when(openClaw.enabled()).thenReturn(true);
        when(messages.existsBySourceMessageId("wamid.oc.image")).thenReturn(false);
        when(whatsApp.downloadMedia("media-1", "wamid.oc.image.jpg"))
                .thenReturn(new WhatsAppService.MediaPayload(bytes, "image/jpeg", "wamid.oc.image.jpg"));
        when(openClaw.canBridgeMedia(bytes)).thenReturn(true);
        when(openClaw.ingestImage(
                bytes, "image/jpeg", "wamid.oc.image.jpg", "wamid.oc.image", "919999999999"))
                .thenReturn(json("""
                        {"ok":true,"result":{"silent":true,"modelUsed":"vision-primary","documentType":"PURCHASE_RECEIPT",
                        "ingestion":{"results":[{"status":"VERIFIED","transactionType":"VENDOR_PAYMENT","category":"VEGETABLES","vendor":"Salman","amount":6700.00}]}}}
                        """));

        int processed = service.process(imagePayload("wamid.oc.image", "media-1", "image/jpeg"));

        assertThat(processed).isEqualTo(1);
        verify(openClaw).ingestImage(
                bytes, "image/jpeg", "wamid.oc.image.jpg", "wamid.oc.image", "919999999999");
        verify(whatsApp).sendText(eq("919999999999"), contains("₹6700 vegetables to Salman"));
    }

    @Test
    void openClawSpreadsheetDmPreviewsThenRequiresExplicitConfirmation() throws Exception {
        byte[] bytes = "csv-bytes".getBytes();
        String jobId = "71d35a26-a50d-4e3d-8c44-8a1c4b73963f";
        when(openClaw.enabled()).thenReturn(true);
        when(messages.existsBySourceMessageId("wamid.oc.csv")).thenReturn(false);
        when(whatsApp.downloadMedia("media-csv", "purchases.csv"))
                .thenReturn(new WhatsAppService.MediaPayload(bytes, "text/csv", "purchases.csv"));
        when(openClaw.canBridgeMedia(bytes)).thenReturn(true);
        when(openClaw.previewSpreadsheet(
                bytes, "text/csv", "purchases.csv", "wamid.oc.csv", "919999999999"))
                .thenReturn(json("""
                        {"ok":true,"preview":{"jobId":"%s","filename":"purchases.csv","recordCount":2}}
                        """.formatted(jobId)));

        int processed = service.process(documentPayload(
                "wamid.oc.csv", "media-csv", "purchases.csv", "text/csv"));

        assertThat(processed).isEqualTo(1);
        verify(openClaw).previewSpreadsheet(
                bytes, "text/csv", "purchases.csv", "wamid.oc.csv", "919999999999");
        verify(whatsApp).sendText(eq("919999999999"), contains("CONFIRM " + jobId));
        verifyNoInteractions(intake);
    }

    @Test
    void confirmSpreadsheetCommandUsesOpenClawConfirmTool() throws Exception {
        String jobId = "71d35a26-a50d-4e3d-8c44-8a1c4b73963f";
        when(openClaw.enabled()).thenReturn(true);
        when(messages.existsBySourceMessageId("wamid.oc.confirm")).thenReturn(false);
        when(openClaw.confirmSpreadsheet(jobId, "wamid.oc.confirm", "919999999999"))
                .thenReturn(json("""
                        {"ok":true,"confirmation":{"jobId":"%s","processed":1,
                        "results":[{"status":"VERIFIED","transactionType":"VENDOR_PAYMENT","category":"VEGETABLES","vendor":"Salman","amount":875.50}]}}
                        """.formatted(jobId)));

        int processed = service.process(textPayload("wamid.oc.confirm", "CONFIRM " + jobId));

        assertThat(processed).isEqualTo(1);
        verify(openClaw).confirmSpreadsheet(jobId, "wamid.oc.confirm", "919999999999");
        verify(whatsApp).sendText(eq("919999999999"), contains("₹875.5 vegetables to Salman"));
    }

    @Test
    void duplicateWhatsAppMessageIsIgnoredBeforeOpenClawOrLegacyProcessing() {
        when(messages.existsBySourceMessageId("wamid.1")).thenReturn(true);

        int processed = service.process(textPayload("wamid.1", "Paid Salman 4200 vegetables"));

        assertThat(processed).isZero();
        verifyNoInteractions(intake);
        verify(messages, never()).save(any());
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(openClaw);
    }

    private NormalizationResult normalization(String status, String amount) {
        return new NormalizationResult(
                UUID.randomUUID(),
                status,
                "VENDOR_PAYMENT",
                "VEGETABLES",
                "Salman",
                null,
                new BigDecimal(amount),
                "INR",
                LocalDate.of(2026, 9, 25),
                "Recorded successfully");
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    private static String textPayload(String id, String text) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"919999999999","timestamp":"1790300000","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(id, text);
    }

    private static String imagePayload(String id, String mediaId, String mime) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"919999999999","timestamp":"1790300000","type":"image","image":{"id":"%s","mime_type":"%s"}}]}}]}]}
                """.formatted(id, mediaId, mime);
    }

    private static String documentPayload(String id, String mediaId, String filename, String mime) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"919999999999","timestamp":"1790300000","type":"document","document":{"id":"%s","filename":"%s","mime_type":"%s"}}]}}]}]}
                """.formatted(id, mediaId, filename, mime);
    }
}
