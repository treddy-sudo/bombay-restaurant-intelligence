package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.NormalizationMapping;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.NormalizationMappingRepository;
import com.bombay.restaurantintelligence.repository.ReviewItemRepository;
import com.bombay.restaurantintelligence.repository.SourceDocumentRepository;
import com.bombay.restaurantintelligence.repository.SourceMessageRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WhatsAppFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired CategoryRepository categories;
    @Autowired NormalizationMappingRepository mappings;
    @Autowired TransactionRepository transactions;
    @Autowired SourceMessageRepository messages;
    @Autowired SourceDocumentRepository documents;
    @Autowired ReviewItemRepository reviews;

    @BeforeEach
    void seedNormalization() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        mappings.save(new NormalizationMapping("vegetables", "CATEGORY", "VEGETABLES", BigDecimal.ONE));
    }

    @Test
    void verificationTextIdempotencyImageReviewAndDashboardAllShareOnePipeline() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        mockMvc.perform(get("/api/whatsapp/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verify-me")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-123"));

        String textPayload = """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.integration.text.1","from":"919999999999","timestamp":"1790300000","type":"text","text":{"body":"Paid Salman 4200 vegetables"}}]}}]}]}
                """;

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.processed").value(1));

        assertThat(messages.count()).isEqualTo(1);
        var afterText = transactions.findTop50ByOrderByCreatedAtDesc();
        assertThat(afterText).hasSize(1);
        assertThat(afterText.getFirst().getStatus()).isEqualTo(TransactionStatus.VERIFIED);
        assertThat(afterText.getFirst().getTransactionType()).isEqualTo(TransactionType.VENDOR_PAYMENT);
        assertThat(afterText.getFirst().getCategory().getCode()).isEqualTo("VEGETABLES");
        assertThat(afterText.getFirst().getVendor().getName()).isEqualTo("Salman");
        assertThat(afterText.getFirst().getAmount()).isEqualByComparingTo("4200.00");

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(0));

        assertThat(messages.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);

        String imagePayload = """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.integration.image.1","from":"919999999999","timestamp":"1790300001","type":"image","image":{"id":"media.integration.1","mime_type":"image/jpeg"}}]}}]}]}
                """;

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imagePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.processed").value(1));

        assertThat(messages.count()).isEqualTo(2);
        assertThat(documents.count()).isEqualTo(1);
        assertThat(reviews.count()).isEqualTo(1);
        assertThat(transactions.findTop50ByOrderByCreatedAtDesc())
                .anySatisfy(t -> assertThat(t.getStatus()).isEqualTo(TransactionStatus.REVIEW_REQUIRED));

        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(4200.00))
                .andExpect(jsonPath("$.selected.vendorPayments").value(4200.00))
                .andExpect(jsonPath("$.vendorSpending[0].name").value("Salman"))
                .andExpect(jsonPath("$.vendorSpending[0].amount").value(4200.00));
    }
}
