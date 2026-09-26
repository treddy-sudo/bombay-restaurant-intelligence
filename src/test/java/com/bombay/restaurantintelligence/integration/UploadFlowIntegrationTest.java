package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.NormalizationMapping;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.NormalizationMappingRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired CategoryRepository categories;
    @Autowired NormalizationMappingRepository mappings;
    @Autowired TransactionRepository transactions;

    @BeforeEach
    void seedNormalization() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        mappings.save(new NormalizationMapping("vegetables", "CATEGORY", "VEGETABLES", BigDecimal.ONE));
    }

    @Test
    void excelPreviewConfirmDeduplicateAndUpdateDashboard() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        byte[] workbook = purchaseWorkbook(today);
        MockMultipartFile file = new MockMultipartFile(
                "file", "purchase-report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        var preview = mockMvc.perform(multipart("/api/intake/uploads/preview")
                        .file(file)
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("purchase-report.xlsx"))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records[0].fields.vendor").value("Salman"))
                .andExpect(jsonPath("$.records[0].fields.category").value("vegetables"))
                .andExpect(jsonPath("$.records[0].fields.amount").value("4200.00"))
                .andReturn();

        JsonNode previewJson = mapper.readTree(preview.getResponse().getContentAsString());
        String jobId = previewJson.get("jobId").asText();

        mockMvc.perform(multipart("/api/intake/uploads/preview")
                        .file(file)
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SOURCE"));

        mockMvc.perform(post("/api/intake/uploads/{jobId}/confirm", jobId)
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.results[0].transactionType").value("VENDOR_PAYMENT"))
                .andExpect(jsonPath("$.results[0].category").value("VEGETABLES"))
                .andExpect(jsonPath("$.results[0].vendor").value("Salman"))
                .andExpect(jsonPath("$.results[0].amount").value(4200.00));

        var stored = transactions.findTop50ByOrderByCreatedAtDesc();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.VERIFIED);
        assertThat(stored.getFirst().getSourceFilename()).isEqualTo("purchase-report.xlsx");
        assertThat(stored.getFirst().getFileChecksum()).isNotBlank();

        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(4200.00))
                .andExpect(jsonPath("$.selected.vendorPayments").value(4200.00))
                .andExpect(jsonPath("$.categoryExpenses[0].name").value("VEGETABLES"))
                .andExpect(jsonPath("$.vendorSpending[0].name").value("Salman"));
    }

    @Test
    void mockImagePreviewConfirmsIntoReviewAndDoesNotAffectDashboard() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        MockMultipartFile image = new MockMultipartFile(
                "file", "receipt.png", "image/png", new byte[]{1, 2, 3, 4, 5});

        var preview = mockMvc.perform(multipart("/api/intake/uploads/preview")
                        .file(image)
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records[0].sourceType").value("IMAGE"))
                .andExpect(jsonPath("$.records[0].confidence").value(0.40))
                .andExpect(jsonPath("$.records[0].fields.documentType").value("UNKNOWN"))
                .andReturn();

        String jobId = mapper.readTree(preview.getResponse().getContentAsString()).get("jobId").asText();

        mockMvc.perform(post("/api/intake/uploads/{jobId}/confirm", jobId)
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("REVIEW_REQUIRED"));

        var stored = transactions.findTop50ByOrderByCreatedAtDesc();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.REVIEW_REQUIRED);

        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(0.00))
                .andExpect(jsonPath("$.selected.sales").value(0.00));
    }

    private static byte[] purchaseWorkbook(LocalDate today) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Purchases");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Vendor");
            header.createCell(1).setCellValue("Category");
            header.createCell(2).setCellValue("Amount");
            header.createCell(3).setCellValue("Date");

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Salman");
            row.createCell(1).setCellValue("vegetables");
            row.createCell(2).setCellValue(4200.00);
            row.createCell(3).setCellValue(today.toString());
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
