package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.config.InternalApiAuthenticationFilter;
import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenClawApprovedDashboardFlowTest {
    private static final String SECRET = "test-shared-secret";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired CategoryRepository categories;

    @BeforeEach
    void seedCategories() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        categories.save(new Category("CASH_SALES", "Cash Sales", "SALES"));
    }

    @Test
    void signedExpandedAnalyticsUsesOnlySpringVerifiedRecords() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        String vendorPayment = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("sourceId", "approved-vendor-1"),
                Map.entry("sender", "manager"),
                Map.entry("sourceType", "WHATSAPP_TEXT"),
                Map.entry("businessDate", today.toString()),
                Map.entry("rawText", "Paid Salman 6500 vegetables"),
                Map.entry("transactionType", "VENDOR_PAYMENT"),
                Map.entry("category", "VEGETABLES"),
                Map.entry("vendor", "Salman"),
                Map.entry("amount", "6500.00"),
                Map.entry("description", "Vegetable payment"),
                Map.entry("confidence", 0.99)));

        signedPost("/api/internal/v1/intake/text-candidate", vendorPayment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        String cashSale = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("sourceId", "approved-cash-sale-1"),
                Map.entry("sender", "manager"),
                Map.entry("sourceType", "WHATSAPP_TEXT"),
                Map.entry("businessDate", today.toString()),
                Map.entry("rawText", "Cash sales 1250"),
                Map.entry("transactionType", "SALE"),
                Map.entry("category", "CASH_SALES"),
                Map.entry("amount", "1250.00"),
                Map.entry("description", "Cash sales"),
                Map.entry("confidence", 0.99)));

        signedPost("/api/internal/v1/intake/text-candidate", cashSale)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        signedGet("/api/internal/v1/analytics/query?intent=VENDOR_SPEND&period=TODAY&subject=Salman")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("VENDOR_SPEND"))
                .andExpect(jsonPath("$.period").value("TODAY"))
                .andExpect(jsonPath("$.metric").value("vendorSpend"))
                .andExpect(jsonPath("$.subject").value("Salman"))
                .andExpect(jsonPath("$.value").value(6500.00))
                .andExpect(jsonPath("$.currency").value("INR"));

        signedGet("/api/internal/v1/analytics/query?intent=CASH_SALES&period=TODAY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("cashSales"))
                .andExpect(jsonPath("$.value").value(1250.00));

        String rangePath = "/api/internal/v1/analytics/query?intent=DATE_RANGE_SALES&from="
                + today + "&to=" + today;
        signedGet(rangePath)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("DATE_RANGE"))
                .andExpect(jsonPath("$.from").value(today.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.value").value(1250.00));
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
