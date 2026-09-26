package com.bombay.restaurantintelligence.whatsapp;

import com.bombay.restaurantintelligence.domain.SourceDocument;
import com.bombay.restaurantintelligence.domain.SourceMessage;
import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.intake.AiExtractionProvider;
import com.bombay.restaurantintelligence.intake.CsvExtractor;
import com.bombay.restaurantintelligence.intake.ExcelExtractor;
import com.bombay.restaurantintelligence.intake.ImageExtractor;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.intake.UploadIngestionService;
import com.bombay.restaurantintelligence.normalization.DuplicateSourceException;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.repository.SourceMessageRepository;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class WhatsAppWebhookService {
    private final ObjectMapper mapper;
    private final SourceMessageRepository messages;
    private final SourceDocumentRepository documents;
    private final WhatsAppService whatsApp;
    private final IntakeAgent intake;
    private final ImageExtractor images;
    private final AiExtractionProvider ai;
    private final ExcelExtractor excel;
    private final CsvExtractor csv;
    private final DocumentStorageService storage;

    public WhatsAppWebhookService(ObjectMapper mapper,
                                  SourceMessageRepository messages,
                                  SourceDocumentRepository documents,
                                  WhatsAppService whatsApp,
                                  IntakeAgent intake,
                                  ImageExtractor images,
                                  AiExtractionProvider ai,
                                  ExcelExtractor excel,
                                  CsvExtractor csv,
                                  DocumentStorageService storage) {
        this.mapper = mapper;
        this.messages = messages;
        this.documents = documents;
        this.whatsApp = whatsApp;
        this.intake = intake;
        this.images = images;
        this.ai = ai;
        this.excel = excel;
        this.csv = csv;
        this.storage = storage;
    }

    @Transactional
    public int process(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            int processed = 0;
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    for (JsonNode msg : value.path("messages")) {
                        String id = msg.path("id").asText();
                        if (id.isBlank() || messages.existsBySourceMessageId(id)) {
                            continue;
                        }

                        String sender = msg.path("from").asText();
                        String type = msg.path("type").asText();
                        Instant received = parseTimestamp(msg.path("timestamp").asText());
                        String raw = "text".equals(type) ? msg.path("text").path("body").asText(null) : null;
                        messages.save(new SourceMessage(id, sender, type, raw, received, body));

                        try {
                            List<NormalizationResult> results = switch (type) {
                                case "text" -> List.of(intake.ingestWhatsAppText(raw, id, sender));
                                case "image" -> processMedia(msg.path("image"), id, sender, true);
                                case "document" -> processMedia(msg.path("document"), id, sender, false);
                                default -> List.of();
                            };
                            if (!results.isEmpty()) {
                                processed++;
                                boolean review = results.stream().anyMatch(r -> "REVIEW_REQUIRED".equals(r.status()));
                                String acknowledgement = review
                                        ? "Received and queued for review."
                                        : acknowledgement(results.getFirst());
                                whatsApp.sendText(sender, acknowledgement);
                            }
                        } catch (DuplicateSourceException e) {
                            whatsApp.sendText(sender, "Already received; duplicate ignored.");
                        }
                    }
                }
            }
            return processed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid WhatsApp webhook payload", e);
        }
    }

    private List<NormalizationResult> processMedia(JsonNode node,
                                                    String messageId,
                                                    String sender,
                                                    boolean image) {
        String mediaId = node.path("id").asText();
        String filename = node.path("filename").asText(image ? messageId + ".jpg" : messageId + ".bin");
        var media = whatsApp.downloadMedia(mediaId, filename);
        String checksum = UploadIngestionService.sha256(media.bytes());
        if (documents.existsByFileChecksum(checksum)) {
            throw new DuplicateSourceException("Media already imported");
        }

        String location = storage.store(media.filename(), media.bytes());
        documents.save(new SourceDocument(media.filename(), media.contentType(), checksum, location, Instant.now()));

        List<IntermediateBusinessRecord> records;
        String lower = media.filename().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            records = excel.extract(media.bytes(), media.filename(), checksum, location);
        } else if (lower.endsWith(".csv")) {
            records = csv.extract(media.bytes(), media.filename(), checksum, location);
        } else if (image) {
            records = images.extract(
                    media.bytes(),
                    media.contentType(),
                    media.filename(),
                    messageId,
                    sender,
                    SourceType.WHATSAPP_IMAGE,
                    location,
                    checksum);
        } else {
            records = ai.extract(media.bytes(), media.contentType(), media.filename(), messageId, sender)
                    .stream()
                    .map(record -> new IntermediateBusinessRecord(
                            SourceType.WHATSAPP_DOCUMENT,
                            messageId + ":" + java.util.UUID.randomUUID(),
                            record.businessDate(),
                            sender,
                            record.fields(),
                            record.confidence(),
                            record.rawText(),
                            media.filename(),
                            location,
                            checksum))
                    .toList();
        }
        return records.stream().map(intake::ingest).toList();
    }

    private static String acknowledgement(NormalizationResult result) {
        String amount = "₹" + result.amount().stripTrailingZeros().toPlainString();
        if ("VENDOR_PAYMENT".equals(result.transactionType()) && result.vendor() != null) {
            return amount + " "
                    + Objects.toString(result.category(), "payment").toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " to " + result.vendor() + " recorded successfully.";
        }
        return amount + " recorded successfully.";
    }

    private static Instant parseTimestamp(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
