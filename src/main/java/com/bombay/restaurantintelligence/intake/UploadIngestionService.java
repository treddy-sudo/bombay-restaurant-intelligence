package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.IngestionJob;
import com.bombay.restaurantintelligence.domain.SourceDocument;
import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.normalization.DuplicateSourceException;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import com.bombay.restaurantintelligence.repository.IngestionJobRepository;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class UploadIngestionService {
    private final ExcelExtractor excel;
    private final CsvExtractor csv;
    private final ImageExtractor images;
    private final DocumentStorageService storage;
    private final IngestionJobRepository jobs;
    private final SourceDocumentRepository documents;
    private final IntakeAgent intake;
    private final ObjectMapper mapper;

    public UploadIngestionService(ExcelExtractor excel,
                                  CsvExtractor csv,
                                  ImageExtractor images,
                                  DocumentStorageService storage,
                                  IngestionJobRepository jobs,
                                  SourceDocumentRepository documents,
                                  IntakeAgent intake,
                                  ObjectMapper mapper) {
        this.excel = excel;
        this.csv = csv;
        this.images = images;
        this.storage = storage;
        this.jobs = jobs;
        this.documents = documents;
        this.intake = intake;
        this.mapper = mapper;
    }

    @Transactional
    public PreviewResponse preview(MultipartFile file) {
        try {
            return previewBytes(
                    Objects.requireNonNullElse(file.getOriginalFilename(), "upload"),
                    file.getContentType(),
                    file.getBytes());
        } catch (DuplicateSourceException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Upload preview failed", e);
        }
    }

    @Transactional
    public PreviewResponse previewBytes(String filename, String contentType, byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("Upload is empty");
            }

            String safeFilename = Objects.requireNonNullElse(filename, "upload");
            String checksum = sha256(bytes);
            if (jobs.existsByChecksum(checksum) || documents.existsByFileChecksum(checksum)) {
                throw new DuplicateSourceException("This file has already been uploaded");
            }

            String location = storage.store(safeFilename, bytes);
            String lower = safeFilename.toLowerCase(Locale.ROOT);
            List<IntermediateBusinessRecord> records;

            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                records = excel.extract(bytes, safeFilename, checksum, location);
            } else if (lower.endsWith(".csv")) {
                records = csv.extract(bytes, safeFilename, checksum, location);
            } else if (isImage(contentType, lower)) {
                records = images.extract(
                        bytes,
                        contentType,
                        safeFilename,
                        checksum,
                        null,
                        SourceType.IMAGE,
                        location,
                        checksum);
            } else {
                throw new IllegalArgumentException("Supported uploads: XLS, XLSX, CSV, PNG, JPG, WEBP");
            }

            String json = mapper.writeValueAsString(records);
            IngestionJob job = jobs.save(new IngestionJob(
                    records.isEmpty() ? "UPLOAD" : records.getFirst().sourceType().name(),
                    safeFilename,
                    "PREVIEW",
                    checksum,
                    json,
                    records.size()));
            return new PreviewResponse(job.getId(), safeFilename, checksum, records.size(), records);
        } catch (DuplicateSourceException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Upload preview failed", e);
        }
    }

    @Transactional
    public ConfirmResponse confirm(UUID jobId) {
        try {
            IngestionJob job = jobs.findById(jobId).orElseThrow();
            if (!"PREVIEW".equals(job.getStatus())) {
                throw new IllegalStateException("Ingestion job is not awaiting confirmation");
            }

            List<IntermediateBusinessRecord> records = mapper.readValue(job.getPayloadJson(), new TypeReference<>() {});
            List<NormalizationResult> results = new ArrayList<>();
            for (var record : records) {
                results.add(intake.ingest(record));
            }

            if (!documents.existsByFileChecksum(job.getChecksum())) {
                documents.save(new SourceDocument(
                        job.getSourceReference(),
                        "application/octet-stream",
                        job.getChecksum(),
                        records.isEmpty() ? null : records.getFirst().originalFileLocation(),
                        Instant.now()));
            }
            job.markImported();
            jobs.save(job);
            return new ConfirmResponse(jobId, results.size(), results);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not confirm import", e);
        }
    }

    private static boolean isImage(String type, String lower) {
        return (type != null && type.startsWith("image/")) || lower.matches(".*\\.(png|jpg|jpeg|webp)$");
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record PreviewResponse(
            UUID jobId,
            String filename,
            String checksum,
            int recordCount,
            List<IntermediateBusinessRecord> records) {}

    public record ConfirmResponse(UUID jobId, int processed, List<NormalizationResult> results) {}
}
