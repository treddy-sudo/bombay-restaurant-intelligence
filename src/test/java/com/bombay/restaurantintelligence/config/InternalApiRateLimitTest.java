package com.bombay.restaurantintelligence.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiRateLimitTest {
    private static final String SECRET = "rate-test-secret";

    @Test
    void validSignedRequestsAreBoundedPerMinute() throws Exception {
        InternalApiAuthenticationFilter filter = new InternalApiAuthenticationFilter(SECRET, 1);

        MockHttpServletResponse first = invoke(filter, UUID.randomUUID().toString());
        MockHttpServletResponse second = invoke(filter, UUID.randomUUID().toString());

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.getContentAsString()).contains("rate limit exceeded");
    }

    private static MockHttpServletResponse invoke(InternalApiAuthenticationFilter filter, String requestId) throws Exception {
        String path = "/api/internal/v1/health";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String canonical = String.join("\n", timestamp, requestId, "GET", path, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader(InternalApiAuthenticationFilter.TIMESTAMP_HEADER, timestamp);
        request.addHeader(InternalApiAuthenticationFilter.REQUEST_ID_HEADER, requestId);
        request.addHeader(InternalApiAuthenticationFilter.SIGNATURE_HEADER, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
