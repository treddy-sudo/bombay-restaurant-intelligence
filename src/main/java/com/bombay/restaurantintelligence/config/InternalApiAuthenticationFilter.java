package com.bombay.restaurantintelligence.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class InternalApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String TIMESTAMP_HEADER = "X-Restaurant-Timestamp";
    public static final String REQUEST_ID_HEADER = "X-Restaurant-Request-Id";
    public static final String SIGNATURE_HEADER = "X-Restaurant-Signature";
    private static final long MAX_SKEW_SECONDS = 300;

    private final byte[] secret;
    private final int maxRequestsPerMinute;
    private final ConcurrentHashMap<String, Long> replayGuard = new ConcurrentHashMap<>();
    private final AtomicLong rateWindowMinute = new AtomicLong(-1);
    private final AtomicInteger rateWindowCount = new AtomicInteger();

    public InternalApiAuthenticationFilter(String secret) {
        this(secret, 120);
    }

    public InternalApiAuthenticationFilter(String secret, int maxRequestsPerMinute) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, jakarta.servlet.FilterChain filterChain)
            throws ServletException, IOException {
        if (secret.length == 0) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Internal API is not configured");
            return;
        }

        CachedBodyRequest wrapped = new CachedBodyRequest(request);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (isBlank(timestamp) || isBlank(requestId) || isBlank(signature)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing internal API signature");
            return;
        }

        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid internal API timestamp");
            return;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - epochSeconds) > MAX_SKEW_SECONDS) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Expired internal API signature");
            return;
        }

        String path = request.getRequestURI();
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            path += "?" + request.getQueryString();
        }
        String bodyHash = sha256(wrapped.body());
        String canonical = String.join("\n", timestamp, requestId, request.getMethod(), path, bodyHash);
        String expected = "sha256=" + hmac(canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid internal API signature");
            return;
        }

        if (rateLimited(now)) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "Internal API rate limit exceeded");
            return;
        }

        replayGuard.entrySet().removeIf(entry -> entry.getValue() < now - MAX_SKEW_SECONDS);
        if (replayGuard.putIfAbsent(requestId, now) != null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Replayed internal API request");
            return;
        }

        request.setAttribute("restaurantRequestId", requestId);
        filterChain.doFilter(wrapped, response);
    }

    private boolean rateLimited(long epochSeconds) {
        long minute = epochSeconds / 60;
        long observed = rateWindowMinute.get();
        if (observed != minute && rateWindowMinute.compareAndSet(observed, minute)) {
            rateWindowCount.set(0);
        }
        return rateWindowCount.incrementAndGet() > maxRequestsPerMinute;
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not calculate internal API signature", e);
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash internal API body", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        private byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
            try {
                return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
