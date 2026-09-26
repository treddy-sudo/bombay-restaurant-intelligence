package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.config.InternalApiAuthenticationFilter;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenClawBatchThreeSpreadsheetFlowTest {
    private static final String SECRET = "test-shared-secret";
    private static final String PREVIEW_PATH = "/api/internal/v1/intake/spreadsheets/preview";

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
    void xlsxPreviewAndConfirmUseExistingDeterministicParserAndUpdateDashboard() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        byte[] workbook = purchaseWorkbook(today);
        String request = previewRequest(
                "purchase-report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbook);

        var preview = signedPost(PREVIEW_PATH, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("purchase-report.xlsx"))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records[0].sourceType").value("EXCEL"))
                .andExpect(jsonPath("$.records[0].fields.vendor").value("Salman"))
                .andExpect(jsonPath("$.records[0].fields.category").value("vegetables"))
                .andExpect(jsonPath("$.records[0].fields.amount").value("4200.00"))
                .andReturn();

        String jobId = mapper.readTree(preview.getResponse().getContentAsString()).get("jobId").asText();
        signedPost("/api/internal/v1/intake/spreadsheets/" + jobId + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.results[0].transactionType").value("VENDOR_PAYMENT"))
                .andExpect(jsonPath("$.results[0].category").value("VEGETABLES"))
                .andExpect(jsonPath("$.results[0].vendor").value("Salman"))
                .andExpect(jsonPath("$.results[0].amount").value(4200.00));

        assertThat(transactions.findTop50ByOrderByCreatedAtDesc()).hasSize(1);
        assertThat(transactions.findTop50ByOrderByCreatedAtDesc().getFirst().getStatus())
                .isEqualTo(TransactionStatus.VERIFIED);

        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(4200.00))
                .andExpect(jsonPath("$.selected.vendorPayments").value(4200.00));
    }

    @Test
    void csvPreviewAndConfirmUseDeterministicCsvParser() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        byte[] csv = ("Vendor,Category,Amount,Date\nSalman,vegetables,875.50," + today + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String request = previewRequest("daily-purchases.csv", "text/csv", csv);

        var preview = signedPost(PREVIEW_PATH, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records[0].sourceType").value("CSV"))
                .andExpect(jsonPath("$.records[0].fields.vendor").value("Salman"))
                .andExpect(jsonPath("$.records[0].fields.amount").value("875.50"))
                .andReturn();

        String jobId = mapper.readTree(preview.getResponse().getContentAsString()).get("jobId").asText();
        signedPost("/api/internal/v1/intake/spreadsheets/" + jobId + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.results[0].amount").value(875.50));
    }

    @Test
    void duplicateSpreadsheetChecksumIsRejectedAtPreview() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        byte[] csv = ("Vendor,Category,Amount,Date\nSalman,vegetables,100," + today + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String request = previewRequest("duplicate.csv", "text/csv", csv);

        signedPost(PREVIEW_PATH, request).andExpect(status().isOk());
        signedPost(PREVIEW_PATH, request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SOURCE"));
    }

    @Test
    void spreadsheetExtensionMustMatchDeclaredContentType() throws Exception {
        byte[] csv = "Vendor,Amount\nSalman,100\n".getBytes(StandardCharsets.UTF_8);
        String request = previewRequest(
                "not-really-xlsx.xlsx",
                "text/csv",
                csv);

        signedPost(PREVIEW_PATH, request)
                .andExpect(status().isBadRequest());
        assertThat(transactions.findTop50ByOrderByCreatedAtDesc()).isEmpty();
    }

    private String previewRequest(String filename, String contentType, byte[] bytes) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("filename", filename);
        request.put("contentType", contentType);
        request.put("fileBase64", Base64.getEncoder().encodeToString(bytes));
        return mapper.writeValueAsString(request);
    }

    private ResultActions signedPost(String path, String body) throws Exception {
        SignedHeaders headers = sign("POST", path, body);
        var builder = post(path)
                .header(InternalApiAuthenticationFilter.TIMESTAMP_HEADER, headers.timestamp())
                .header(InternalApiAuthenticationFilter.REQUEST_ID_HEADER, headers.requestId())
                .header(InternalApiAuthenticationFilter.SIGNATURE_HEADER, headers.signature());
        if (!body.isEmpty()) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder);
    }

    private static SignedHeaders sign(String method, String path, String body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String requestId = UUID.randomUUID().toString();
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", timestamp, requestId, method, path, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return new SignedHeaders(timestamp, requestId, signature);
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

    private record SignedHeaders(String timestamp, String requestId, String signature) {}
}
