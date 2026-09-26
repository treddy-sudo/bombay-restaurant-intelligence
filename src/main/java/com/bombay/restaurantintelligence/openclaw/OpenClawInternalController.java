package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.analytics.AnalyticsAgent;
import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.intake.UploadIngestionService;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/internal/v1")
public class OpenClawInternalController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_SPREADSHEET_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = 14_000_000;
    private static final Set<String> CSV_CONTENT_TYPES = Set.of("text/csv", "application/csv");
    private static final String XLS_CONTENT_TYPE = "application/vnd.ms-excel";
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final IntakeAgent intake;
    private final AnalyticsAgent analytics;
    private final OpenClawImageIntakeService imageIntake;
    private final UploadIngestionService uploads;

    public OpenClawInternalController(IntakeAgent intake,
                                      AnalyticsAgent analytics,
                                      OpenClawImageIntakeService imageIntake,
                                      UploadIngestionService uploads) {
        this.intake = intake;
        this.analytics = analytics;
        this.imageIntake = imageIntake;
        this.uploads = uploads;
    }

    @PostMapping("/intake/text-candidate")
    public NormalizationResult ingestTextCandidate(@Valid @RequestBody TextCandidateRequest request) {
        SourceType sourceType = request.sourceType() == null ? SourceType.MANUAL_TEXT : request.sourceType();
        if (sourceType != SourceType.MANUAL_TEXT && sourceType != SourceType.WHATSAPP_TEXT) {
            throw new IllegalArgumentException("Text candidate sourceType must be MANUAL_TEXT or WHATSAPP_TEXT");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, "transactionType", request.transactionType());
        putIfPresent(fields, "category", request.category());
        putIfPresent(fields, "vendor", request.vendor());
        putIfPresent(fields, "employee", request.employee());
        putIfPresent(fields, "amount", request.amount());
        putIfPresent(fields, "description", request.description());
        putIfPresent(fields, "context", request.context());

        IntermediateBusinessRecord candidate = new IntermediateBusinessRecord(
                sourceType,
                request.sourceId(),
                request.businessDate(),
                request.sender(),
                fields,
                request.confidence(),
                request.rawText(),
                null,
                null,
                null);
        return intake.ingest(candidate);
    }

    @PostMapping("/intake/image-candidates")
    public OpenClawImageIntakeService.ImageIngestionResponse ingestImageCandidates(
            @Valid @RequestBody OpenClawImageCandidateRequest request) {
        return imageIntake.ingest(request);
    }

    @PostMapping("/intake/spreadsheets/preview")
    public UploadIngestionService.PreviewResponse previewSpreadsheet(
            @Valid @RequestBody SpreadsheetPreviewRequest request) {
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        validateSpreadsheetType(request.filename(), contentType);
        if (request.fileBase64().length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("Spreadsheet exceeds 10 MB decoded limit");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.fileBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("fileBase64 is not valid Base64", e);
        }
        if (bytes.length == 0 || bytes.length > MAX_SPREADSHEET_BYTES) {
            throw new IllegalArgumentException("Spreadsheet must be between 1 byte and 10 MB");
        }
        return uploads.previewBytes(request.filename(), contentType, bytes);
    }

    @PostMapping("/intake/spreadsheets/{jobId}/confirm")
    public UploadIngestionService.ConfirmResponse confirmSpreadsheet(@PathVariable UUID jobId) {
        return uploads.confirm(jobId);
    }

    @GetMapping("/analytics/query")
    public AnalyticsAnswer query(@RequestParam DashboardIntent intent) {
        return switch (intent) {
            case TODAY_SALES -> {
                LocalDate today = LocalDate.now(BUSINESS_ZONE);
                BigDecimal sales = analytics.dashboard(today, today).selected().sales();
                yield new AnalyticsAnswer(intent, today, today, "sales", sales, "INR");
            }
        };
    }

    private static void validateSpreadsheetType(String filename, String contentType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean valid = lower.endsWith(".csv")
                ? CSV_CONTENT_TYPES.contains(contentType)
                : lower.endsWith(".xls")
                    ? XLS_CONTENT_TYPE.equals(contentType)
                    : lower.endsWith(".xlsx") && XLSX_CONTENT_TYPE.equals(contentType);
        if (!valid) {
            throw new IllegalArgumentException("Spreadsheet filename extension does not match an allowed CSV/XLS/XLSX content type");
        }
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.trim());
        }
    }

    public record TextCandidateRequest(
            @NotBlank String sourceId,
            String sender,
            SourceType sourceType,
            LocalDate businessDate,
            @NotBlank String rawText,
            String transactionType,
            String category,
            String vendor,
            String employee,
            @NotBlank String amount,
            String description,
            String context,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence) {}

    public record SpreadsheetPreviewRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank String contentType,
            @NotBlank String fileBase64) {}

    public record AnalyticsAnswer(
            DashboardIntent intent,
            LocalDate from,
            LocalDate to,
            String metric,
            BigDecimal value,
            String currency) {}
}
