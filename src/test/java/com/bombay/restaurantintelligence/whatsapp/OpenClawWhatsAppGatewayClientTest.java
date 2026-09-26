package com.bombay.restaurantintelligence.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenClawWhatsAppGatewayClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void routeTextInvokesOnlyRestaurantToolWithWhatsAppContextAndIdempotency() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> channel = new AtomicReference<>();
        AtomicReference<String> target = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/invoke", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            channel.set(exchange.getRequestHeaders().getFirst("x-openclaw-message-channel"));
            target.set(exchange.getRequestHeaders().getFirst("x-openclaw-message-to"));
            byte[] response = "{\"ok\":true,\"result\":{\"ok\":true,\"result\":{\"classification\":\"IGNORE\",\"silent\":true}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenClawWhatsAppGatewayClient(
                    WebClient.builder(),
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "gateway-secret",
                    1_250_000);

            JsonNode result = client.routeText(
                    "Paid Salman 4200 vegetables", "wamid.123", "919999999999");

            assertThat(result.path("ok").asBoolean()).isTrue();
            JsonNode request = mapper.readTree(body.get());
            assertThat(request.path("tool").asText()).isEqualTo("restaurant_route_text");
            assertThat(request.path("idempotencyKey").asText())
                    .isEqualTo("whatsapp-cloud:wamid.123:restaurant_route_text");
            assertThat(request.path("args").path("sourceId").asText()).isEqualTo("wamid.123");
            assertThat(request.path("args").path("sourceType").asText()).isEqualTo("WHATSAPP_TEXT");
            assertThat(auth.get()).isEqualTo("Bearer gateway-secret");
            assertThat(channel.get()).isEqualTo("whatsapp-cloud");
            assertThat(target.get()).isEqualTo("919999999999");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enabledBridgeFailsFastWithoutGatewayCredentials() {
        assertThatThrownBy(() -> new OpenClawWhatsAppGatewayClient(
                WebClient.builder(), true, "", "", 1_250_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENCLAW_GATEWAY_URL");
    }

    @Test
    void mediaLimitProtectsOpenClawToolsInvokeBodyLimit() {
        var client = new OpenClawWhatsAppGatewayClient(
                WebClient.builder(), false, "", "", 100);

        assertThat(client.canBridgeMedia(new byte[100])).isTrue();
        assertThat(client.canBridgeMedia(new byte[101])).isFalse();
        assertThat(client.canBridgeMedia(new byte[0])).isFalse();
    }
}
