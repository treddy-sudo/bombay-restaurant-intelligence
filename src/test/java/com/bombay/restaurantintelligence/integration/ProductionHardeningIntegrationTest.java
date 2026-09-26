package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.config.InternalApiAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionHardeningIntegrationTest {
    private static final String SECRET = "test-shared-secret";

    @Autowired MockMvc mockMvc;

    @Test
    void signedInternalHealthChecksDatabaseStorageAndAnalytics() throws Exception {
        var headers = sign("GET", "/api/internal/v1/health", "");

        mockMvc.perform(get("/api/internal/v1/health")
                        .header(InternalApiAuthenticationFilter.TIMESTAMP_HEADER, headers.timestamp())
                        .header(InternalApiAuthenticationFilter.REQUEST_ID_HEADER, headers.requestId())
                        .header(InternalApiAuthenticationFilter.SIGNATURE_HEADER, headers.signature()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.status").value("UP"))
                .andExpect(jsonPath("$.components.analytics.status").value("UP"));
    }

    @Test
    void internalHealthStillRequiresHmacSignature() throws Exception {
        mockMvc.perform(get("/api/internal/v1/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing internal API signature"));
    }

    private static SignatureHeaders sign(String method, String path, String body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String requestId = UUID.randomUUID().toString();
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", timestamp, requestId, method, path, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return new SignatureHeaders(timestamp, requestId, signature);
    }

    private record SignatureHeaders(String timestamp, String requestId, String signature) {}
}
