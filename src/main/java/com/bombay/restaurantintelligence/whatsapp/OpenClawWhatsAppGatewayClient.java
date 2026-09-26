package com.bombay.restaurantintelligence.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpenClawWhatsAppGatewayClient {
    private final WebClient client;
    private final boolean enabled;
    private final String gatewayBaseUrl;
    private final String gatewayToken;
    private final int maxMediaBytes;

    public OpenClawWhatsAppGatewayClient(
            WebClient.Builder builder,
            @Value("${app.whatsapp.openclaw.enabled:false}") boolean enabled,
            @Value("${app.whatsapp.openclaw.gateway-url:}") String gatewayBaseUrl,
            @Value("${app.whatsapp.openclaw.gateway-token:}") String gatewayToken,
            @Value("${app.whatsapp.openclaw.max-media-bytes:1250000}") int maxMediaBytes) {
        this.client = builder.build();
        this.enabled = enabled;
        this.gatewayBaseUrl = stripTrailingSlash(gatewayBaseUrl);
        this.gatewayToken = gatewayToken;
        this.maxMediaBytes = maxMediaBytes;
        if (enabled && (this.gatewayBaseUrl.isBlank() || gatewayToken.isBlank())) {
            throw new IllegalStateException(
                    "OpenClaw WhatsApp routing requires OPENCLAW_GATEWAY_URL and OPENCLAW_GATEWAY_TOKEN");
        }
        if (maxMediaBytes < 1 || maxMediaBytes > 1_500_000) {
            throw new IllegalStateException("OpenClaw WhatsApp max media bytes must be between 1 and 1500000");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean canBridgeMedia(byte[] bytes) {
        return bytes != null && bytes.length > 0 && bytes.length <= maxMediaBytes;
    }

    public JsonNode routeText(String text, String messageId, String sender) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("text", text);
        args.put("sourceId", messageId);
        args.put("sender", sender);
        args.put("sourceType", "WHATSAPP_TEXT");
        return invoke("restaurant_route_text", args, messageId, sender);
    }

    public JsonNode ingestImage(byte[] bytes,
                                String contentType,
                                String filename,
                                String messageId,
                                String sender) {
        if (!canBridgeMedia(bytes)) {
            throw new IllegalArgumentException("Image exceeds the configured OpenClaw WhatsApp bridge limit");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("imageBase64", Base64.getEncoder().encodeToString(bytes));
        args.put("contentType", contentType);
        args.put("filename", filename);
        args.put("sourceId", messageId);
        args.put("sender", sender);
        args.put("sourceType", "WHATSAPP_IMAGE");
        return invoke("restaurant_ingest_image", args, messageId, sender);
    }

    public JsonNode previewSpreadsheet(byte[] bytes,
                                       String contentType,
                                       String filename,
                                       String messageId,
                                       String sender) {
        if (!canBridgeMedia(bytes)) {
            throw new IllegalArgumentException("Spreadsheet exceeds the configured OpenClaw WhatsApp bridge limit");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("fileBase64", Base64.getEncoder().encodeToString(bytes));
        args.put("contentType", contentType);
        args.put("filename", filename);
        return invoke("restaurant_preview_spreadsheet", args, messageId, sender);
    }

    public JsonNode confirmSpreadsheet(String jobId, String messageId, String sender) {
        return invoke(
                "restaurant_confirm_spreadsheet",
                Map.of("jobId", jobId),
                messageId,
                sender);
    }

    private JsonNode invoke(String tool,
                            Map<String, Object> args,
                            String messageId,
                            String sender) {
        if (!enabled) {
            throw new IllegalStateException("OpenClaw WhatsApp routing is disabled");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tool", tool);
        request.put("args", args);
        request.put("idempotencyKey", "whatsapp-cloud:" + messageId + ":" + tool);

        JsonNode envelope = client.post()
                .uri(gatewayBaseUrl + "/tools/invoke")
                .headers(headers -> {
                    headers.setBearerAuth(gatewayToken);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("x-openclaw-message-channel", "whatsapp-cloud");
                    headers.set("x-openclaw-message-to", sender);
                    headers.set("x-openclaw-thread-id", sender);
                })
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (envelope == null || !envelope.path("ok").asBoolean(false)) {
            String message = envelope == null
                    ? "OpenClaw Gateway returned an empty response"
                    : envelope.path("error").path("message").asText("OpenClaw tool invocation failed");
            throw new IllegalStateException(message);
        }
        JsonNode result = envelope.get("result");
        if (result == null || result.isNull()) {
            throw new IllegalStateException("OpenClaw Gateway tool result is missing");
        }
        return result;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("/+$", "");
    }
}
