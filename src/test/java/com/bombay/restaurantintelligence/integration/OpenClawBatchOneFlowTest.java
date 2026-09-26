package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.config.InternalApiAuthenticationFilter;
import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenClawBatchOneFlowTest {
    private static final String SECRET = "test-shared-secret";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired CategoryRepository categories;
    @Autowired TransactionRepository transactions;

    @BeforeEach
    void seedCategories() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        categories.save(new Category("IN_STORE_SALES", "In-store Sales", "SALES"));
    }

    @Test
    void ollamaCandidateMustPassNormalizationAndAnalyticsRemainJavaAuthoritative() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        String paymentCandidate = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("sourceId", "batch1-payment-1"),
                Map.entry("sender", "batch1-manager"),
                Map.entry("sourceType", "MANUAL_TEXT"),
                Map.entry("businessDate", today.toString()),
                Map.entry("rawText", "Paid Salman 6500 vegetables"),
                Map.entry("transactionType", "VENDOR_PAYMENT"),
                Map.entry("category", "VEGETABLES"),
                Map.entry("vendor", "Salman"),
                Map.entry("amount", "6500.00"),
                Map.entry("description", "Vegetable payment to Salman"),
                Map.entry("confidence", 0.96)));

        signedPost("/api/internal/v1/intake/text-candidate", paymentCandidate)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.transactionType").value("VENDOR_PAYMENT"))
                .andExpect(jsonPath("$.category").value("VEGETABLES"))
                .andExpect(jsonPath("$.vendor").value("Salman"))
                .andExpect(jsonPath("$.amount").value(6500.00));

        var payment = transactions.findTop50ByOrderByCreatedAtDesc().getFirst();
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.VERIFIED);
        assertThat(payment.getTransactionType()).isEqualTo(TransactionType.VENDOR_PAYMENT);
        assertThat(payment.getAmount()).isEqualByComparingTo("6500.00");

        String saleCandidate = mapper.writeValueAsString(Map.of(
                "sourceId", "batch1-sale-1",
                "sender", "batch1-manager",
                "sourceType", "MANUAL_TEXT",
                "businessDate", today.toString(),
                "rawText", "Today's in store sales 84560",
                "transactionType", "SALE",
                "category", "IN_STORE_SALES",
                "amount", "84560.00",
                "description", "Daily in-store sales",
                "confidence", 0.99));

        signedPost("/api/internal/v1/intake/text-candidate", saleCandidate)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        signedGet("/api/internal/v1/analytics/query?intent=TODAY_SALES")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("TODAY_SALES"))
                .andExpect(jsonPath("$.metric").value("sales"))
                .andExpect(jsonPath("$.value").value(84560.00))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void unsignedInternalRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/internal/v1/analytics/query?intent=TODAY_SALES"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions signedPost(String path, String body) throws Exception {
        SignedHeaders headers = sign("POST", path, body);
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(InternalApiAuthenticationFilter.TIMESTAMP_HEADER, headers.timestamp())
                .header(InternalApiAuthenticationFilter.REQUEST_ID_HEADER, headers.requestId())
                .header(InternalApiAuthenticationFilter.SIGNATURE_HEADER, headers.signature()));
    }

    private ResultActions signedGet(String path) throws Exception {
        SignedHeaders headers = sign("GET", path, "");
        return mockMvc.perform(get(path)
                .header(InternalApiAuthenticationFilter.TIMESTAMP_HEADER, headers.timestamp())
                .header(InternalApiAuthenticationFilter.REQUEST_ID_HEADER, headers.requestId())
                .header(InternalApiAuthenticationFilter.SIGNATURE_HEADER, headers.signature()));
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

    private record SignedHeaders(String timestamp, String requestId, String signature) {}
}
