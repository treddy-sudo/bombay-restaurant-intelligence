package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.domain.SourceDocument;
import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.intake.UploadIngestionService;
import com.bombay.restaurantintelligence.normalization.DuplicateSourceException;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OpenClawImageIntakeService {
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = 14_000_000;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final DocumentStorageService storage;
    private final SourceDocumentRepository documents;
    private final IntakeAgent intake;

    public OpenClawImageIntakeService(DocumentStorageService storage,
                                      SourceDocumentRepository documents,
                                      IntakeAgent intake) {
        this.storage = storage;
        this.documents = documents;
        this.intake = intake;
    }

    @Transactional
    public ImageIngestionResponse ingest(OpenClawImageCandidateRequest request) {
        SourceType sourceType = request.sourceType() == null ? SourceType.IMAGE : request.sourceType();
        if (sourceType != SourceType.IMAGE && sourceType != SourceType.WHATSAPP_IMAGE) {
            throw new IllegalArgumentException("Image candidate sourceType must be IMAGE or WHATSAPP_IMAGE");
        }

        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Supported image content types: image/jpeg, image/png, image/webp");
        }
        validateFilename(request.filename(), contentType);

        if (request.imageBase64().length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("Image exceeds 10 MB decoded limit");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.imageBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imageBase64 is not valid Base64", e);
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image must be between 1 byte and 10 MB");
        }

        String checksum = UploadIngestionService.sha256(bytes);
        if (request.fileChecksum() != null
                && !request.fileChecksum().isBlank()
                && !checksum.equalsIgnoreCase(request.fileChecksum().trim())) {
            throw new IllegalArgumentException("Image checksum does not match fileChecksum");
        }
        if (documents.existsByFileChecksum(checksum)) {
            throw new DuplicateSourceException("Image already imported");
        }

        String safeFilename = sanitizeFilename(request.filename());
        String location = storage.store(safeFilename, contentType, bytes);
        documents.save(new SourceDocument(safeFilename, contentType, checksum, location, Instant.now()));

        List<NormalizationResult> results = new ArrayList<>(request.records().size());
        for (int i = 0; i < request.records().size(); i++) {
            OpenClawImageCandidateRequest.CandidateRecord extracted = request.records().get(i);
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, "transactionType", extracted.transactionType());
            putIfPresent(fields, "category", extracted.category());
            putIfPresent(fields, "vendor", extracted.vendor());
            putIfPresent(fields, "employee", extracted.employee());
            putIfPresent(fields, "amount", extracted.amount());
            putIfPresent(fields, "description", extracted.description());
            putIfPresent(fields, "context", extracted.context());
            putIfPresent(fields, "documentType", request.documentType());

            IntermediateBusinessRecord candidate = new IntermediateBusinessRecord(
                    sourceType,
                    request.sourceId() + ":" + (i + 1),
                    extracted.businessDate(),
                    request.sender(),
                    fields,
                    extracted.confidence(),
                    extracted.rawText(),
                    safeFilename,
                    location,
                    checksum);
            results.add(intake.ingest(candidate));
        }

        return new ImageIngestionResponse(checksum, request.documentType(), results.size(), results);
    }

    private static void validateFilename(String filename, String contentType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean matches = switch (contentType) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "image/webp" -> lower.endsWith(".webp");
            default -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException("Filename extension does not match image content type");
        }
    }

    private static String sanitizeFilename(String filename) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "image" : safe;
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.trim());
        }
    }

    public record ImageIngestionResponse(
            String checksum,
            String documentType,
            int processed,
            List<NormalizationResult> results) {}
}
