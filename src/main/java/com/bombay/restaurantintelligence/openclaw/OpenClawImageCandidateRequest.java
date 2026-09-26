package com.bombay.restaurantintelligence.openclaw;

import com.bombay.restaurantintelligence.domain.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OpenClawImageCandidateRequest(
        @NotBlank String sourceId,
        String sender,
        SourceType sourceType,
        @NotBlank @Size(max = 255) String filename,
        @NotBlank String contentType,
        @NotBlank String imageBase64,
        String fileChecksum,
        @Size(max = 80) String documentType,
        @NotEmpty @Size(max = 100) List<@Valid CandidateRecord> records) {

    public record CandidateRecord(
            LocalDate businessDate,
            String rawText,
            String transactionType,
            String category,
            String vendor,
            String employee,
            @NotBlank String amount,
            String description,
            String context,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence) {}
}
