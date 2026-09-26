package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.analytics.AnalyticsAgent;
import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/v1")
public class OpenClawInternalController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final IntakeAgent intake;
    private final AnalyticsAgent analytics;
    private final OpenClawImageIntakeService imageIntake;

    public OpenClawInternalController(IntakeAgent intake,
                                      AnalyticsAgent analytics,
                                      OpenClawImageIntakeService imageIntake) {
        this.intake = intake;
        this.analytics = analytics;
        this.imageIntake = imageIntake;
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

    public record AnalyticsAnswer(
            DashboardIntent intent,
            LocalDate from,
            LocalDate to,
            String metric,
            BigDecimal value,
            String currency) {}
}
