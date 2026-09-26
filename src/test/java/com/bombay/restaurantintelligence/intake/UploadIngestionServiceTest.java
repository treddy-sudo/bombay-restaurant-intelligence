package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.normalization.DuplicateSourceException;
import com.bombay.restaurantintelligence.repository.IngestionJobRepository;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.storage.DocumentStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadIngestionServiceTest {
    @Mock ExcelExtractor excel;
    @Mock CsvExtractor csv;
    @Mock ImageExtractor images;
    @Mock DocumentStorageService storage;
    @Mock IngestionJobRepository jobs;
    @Mock SourceDocumentRepository docs;
    @Mock IntakeAgent intake;

    @Test
    void duplicateChecksumIsRejected() {
        when(jobs.existsByChecksum(anyString())).thenReturn(true);
        var service = new UploadIngestionService(
                excel, csv, images, storage, jobs, docs, intake, new ObjectMapper());
        var file = new MockMultipartFile(
                "file", "purchase.csv", "text/csv", "a,b\n1,2".getBytes());

        assertThatThrownBy(() -> service.preview(file))
                .isInstanceOf(DuplicateSourceException.class);
    }

    @Test
    void checksumIsDeterministic() {
        assertThat(UploadIngestionService.sha256("abc".getBytes()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void storageContentTypeUsesExtensionWhenUpstreamTypeIsGeneric() {
        assertThat(UploadIngestionService.storageContentType("daily-sales.xlsx", "application/octet-stream"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(UploadIngestionService.storageContentType("receipt.jpg", null))
                .isEqualTo("image/jpeg");
    }

    @Test
    void storageContentTypePreservesSpecificTypeAndFallsBackSafely() {
        assertThat(UploadIngestionService.storageContentType("sales.csv", "text/csv"))
                .isEqualTo("text/csv");
        assertThat(UploadIngestionService.storageContentType("unknown.bin", null))
                .isEqualTo("application/octet-stream");
    }
}
