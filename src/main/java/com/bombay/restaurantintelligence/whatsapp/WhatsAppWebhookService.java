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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhatsAppWebhookService {
    private static final Pattern CONFIRM_SPREADSHEET = Pattern.compile(
            "(?i)^\\s*confirm\\s+([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\s*$");

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
    private final OpenClawWhatsAppGatewayClient openClaw;

    public WhatsAppWebhookService(ObjectMapper mapper,
                                  SourceMessageRepository messages,
                                  SourceDocumentRepository documents,
                                  WhatsAppService whatsApp,
                                  IntakeAgent intake,
                                  ImageExtractor images,
                                  AiExtractionProvider ai,
                                  ExcelExtractor excel,
                                  CsvExtractor csv,
                                  DocumentStorageService storage,
                                  OpenClawWhatsAppGatewayClient openClaw) {
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
        this.openClaw = openClaw;
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
                            boolean handled = openClaw.enabled()
                                    ? processWithOpenClaw(msg, id, sender, type, raw)
                                    : processLegacy(msg, id, sender, type, raw);
                            if (handled) processed++;
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

    private boolean processWithOpenClaw(JsonNode message,
                                        String messageId,
                                        String sender,
                                        String type,
                                        String rawText) {
        return switch (type) {
            case "text" -> processOpenClawText(rawText, messageId, sender);
            case "image" -> processOpenClawImage(message.path("image"), messageId, sender);
            case "document" -> processOpenClawDocument(message.path("document"), messageId, sender);
            default -> false;
        };
    }

    private boolean processOpenClawText(String text, String messageId, String sender) {
        if (text == null || text.isBlank()) return false;

        Matcher confirm = CONFIRM_SPREADSHEET.matcher(text);
        if (confirm.matches()) {
            JsonNode toolOutput = openClaw.confirmSpreadsheet(confirm.group(1), messageId, sender);
            JsonNode confirmation = unwrapToolOutput(toolOutput, "confirmation");
            List<JsonNode> results = nodes(confirmation.path("results"));
            if (results.isEmpty()) {
                whatsApp.sendText(sender, "Spreadsheet confirmation completed with no imported rows.");
            } else {
                whatsApp.sendText(sender, acknowledgement(results));
            }
            return true;
        }

        JsonNode toolOutput = openClaw.routeText(text, messageId, sender);
        JsonNode result = unwrapToolOutput(toolOutput, "result");
        String classification = result.path("classification").asText();

        if ("DASHBOARD_QUESTION".equals(classification)) {
            String reply = result.path("reply").asText();
            if (!reply.isBlank()) whatsApp.sendText(sender, reply);
            return true;
        }
        if ("DATA_TEXT".equals(classification)) {
            JsonNode record = result.path("record");
            if (!record.isMissingNode() && !record.isNull()) {
                whatsApp.sendText(sender, acknowledgement(List.of(record)));
            }
            return true;
        }
        return "IGNORE".equals(classification);
    }

    private boolean processOpenClawImage(JsonNode node, String messageId, String sender) {
        String mediaId = node.path("id").asText();
        if (mediaId.isBlank()) return false;

        String declaredMime = node.path("mime_type").asText("image/jpeg");
        String fallbackFilename = messageId + imageExtension(declaredMime);
        WhatsAppService.MediaPayload media = whatsApp.downloadMedia(mediaId, fallbackFilename);
        String filename = messageId + imageExtension(media.contentType());

        if (!isOpenClawImageType(media.contentType()) || !openClaw.canBridgeMedia(media.bytes())) {
            List<NormalizationResult> results = processDownloadedMedia(media, messageId, sender, true);
            sendLegacyAcknowledgement(sender, results);
            return !results.isEmpty();
        }

        JsonNode toolOutput = openClaw.ingestImage(
                media.bytes(), media.contentType(), filename, messageId, sender);
        JsonNode result = unwrapToolOutput(toolOutput, "result");
        List<JsonNode> results = nodes(result.path("ingestion").path("results"));
        if (!results.isEmpty()) whatsApp.sendText(sender, acknowledgement(results));
        return !results.isEmpty();
    }

    private boolean processOpenClawDocument(JsonNode node, String messageId, String sender) {
        String mediaId = node.path("id").asText();
        if (mediaId.isBlank()) return false;
        String filename = node.path("filename").asText(messageId + ".bin");
        WhatsAppService.MediaPayload media = whatsApp.downloadMedia(mediaId, filename);

        if (isSpreadsheet(media.filename(), media.contentType()) && openClaw.canBridgeMedia(media.bytes())) {
            JsonNode toolOutput = openClaw.previewSpreadsheet(
                    media.bytes(), media.contentType(), media.filename(), messageId, sender);
            JsonNode preview = unwrapToolOutput(toolOutput, "preview");
            String jobId = preview.path("jobId").asText();
            int rows = preview.path("recordCount").asInt(0);
            if (jobId.isBlank()) {
                throw new IllegalStateException("OpenClaw spreadsheet preview did not return a job id");
            }
            whatsApp.sendText(
                    sender,
                    "Previewed " + rows + " row" + (rows == 1 ? "" : "s") + " from " + media.filename()
                            + ". Reply CONFIRM " + jobId + " to import.");
            return true;
        }

        List<NormalizationResult> results = processDownloadedMedia(media, messageId, sender, false);
        sendLegacyAcknowledgement(sender, results);
        return !results.isEmpty();
    }

    private boolean processLegacy(JsonNode message,
                                  String messageId,
                                  String sender,
                                  String type,
                                  String rawText) {
        List<NormalizationResult> results = switch (type) {
            case "text" -> List.of(intake.ingestWhatsAppText(rawText, messageId, sender));
            case "image" -> processMedia(message.path("image"), messageId, sender, true);
            case "document" -> processMedia(message.path("document"), messageId, sender, false);
            default -> List.of();
        };
        sendLegacyAcknowledgement(sender, results);
        return !results.isEmpty();
    }

    private List<NormalizationResult> processMedia(JsonNode node,
                                                    String messageId,
                                                    String sender,
                                                    boolean image) {
        String mediaId = node.path("id").asText();
        String filename = node.path("filename").asText(image ? messageId + ".jpg" : messageId + ".bin");
        WhatsAppService.MediaPayload media = whatsApp.downloadMedia(mediaId, filename);
        return processDownloadedMedia(media, messageId, sender, image);
    }

    private List<NormalizationResult> processDownloadedMedia(WhatsAppService.MediaPayload media,
                                                              String messageId,
                                                              String sender,
                                                              boolean image) {
        String checksum = UploadIngestionService.sha256(media.bytes());
        if (documents.existsByFileChecksum(checksum)) {
            throw new DuplicateSourceException("Media already imported");
        }

        String location = storage.store(media.filename(), media.bytes());
        documents.save(new SourceDocument(
                media.filename(), media.contentType(), checksum, location, Instant.now()));

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
                            messageId + ":" + UUID.randomUUID(),
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

    private void sendLegacyAcknowledgement(String sender, List<NormalizationResult> results) {
        if (results.isEmpty()) return;
        boolean review = results.stream().anyMatch(r -> "REVIEW_REQUIRED".equals(r.status()));
        String ack = review
                ? "Received and queued for review."
                : acknowledgement(results.getFirst());
        whatsApp.sendText(sender, ack);
    }

    private static JsonNode unwrapToolOutput(JsonNode toolOutput, String field) {
        if (toolOutput == null || toolOutput.isNull()) {
            throw new IllegalStateException("OpenClaw tool returned no result");
        }
        if (toolOutput.has("ok") && !toolOutput.path("ok").asBoolean(true)) {
            throw new IllegalStateException(toolOutput.path("error").asText("OpenClaw restaurant tool failed"));
        }
        JsonNode value = toolOutput.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("OpenClaw restaurant tool result is missing " + field);
        }
        return value;
    }

    private static List<JsonNode> nodes(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(out::add);
        return out;
    }

    private static String acknowledgement(List<JsonNode> results) {
        boolean review = results.stream().anyMatch(r -> "REVIEW_REQUIRED".equals(r.path("status").asText()));
        if (review) {
            return results.size() == 1
                    ? "Received and queued for review."
                    : "Received " + results.size() + " records; at least one is queued for review.";
        }
        if (results.size() > 1) {
            return "Recorded " + results.size() + " records successfully.";
        }

        JsonNode result = results.getFirst();
        BigDecimal amount = result.path("amount").decimalValue();
        String formattedAmount = "₹" + amount.stripTrailingZeros().toPlainString();
        String type = result.path("transactionType").asText();
        String vendor = nullableText(result, "vendor");
        String category = nullableText(result, "category");
        if ("VENDOR_PAYMENT".equals(type) && vendor != null) {
            return formattedAmount + " "
                    + Objects.toString(category, "payment").toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " to " + vendor + " recorded successfully.";
        }
        return formattedAmount + " recorded successfully.";
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

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static boolean isOpenClawImageType(String contentType) {
        return "image/jpeg".equalsIgnoreCase(contentType)
                || "image/png".equalsIgnoreCase(contentType)
                || "image/webp".equalsIgnoreCase(contentType);
    }

    private static String imageExtension(String contentType) {
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        return ".jpg";
    }

    private static boolean isSpreadsheet(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "text/csv".equalsIgnoreCase(contentType)
                    || "application/csv".equalsIgnoreCase(contentType);
        }
        if (lower.endsWith(".xls")) {
            return "application/vnd.ms-excel".equalsIgnoreCase(contentType);
        }
        return lower.endsWith(".xlsx")
                && "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                .equalsIgnoreCase(contentType);
    }

    private static Instant parseTimestamp(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
