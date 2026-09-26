package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.repository.AuditLogRepository;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.NormalizationMappingRepository;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
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
class ReviewCorrectionFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired CategoryRepository categories;
    @Autowired NormalizationMappingRepository mappings;
    @Autowired AuditLogRepository audits;
    @Autowired TransactionRepository transactions;

    @BeforeEach
    void seedCategory() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
    }

    @Test
    void approvalAuditsLearnsAliasAndFutureEntryAutoPostsWhileRejectionStaysExcluded() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        JsonNode first = postManual("Paid Salman 500 for mystery supplies");
        assertThat(first.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        UUID firstTransactionId = UUID.fromString(first.get("transactionId").asText());

        assertDashboardExpenses(today, 0.00);
        String reviewId = reviewIdFor(firstTransactionId);

        mockMvc.perform(post("/api/reviews/{id}/approve", reviewId)
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryCode":"VEGETABLES",
                                  "vendor":"Salman",
                                  "amount":500.00,
                                  "businessDate":"%s",
                                  "rawTerm":"mystery supplies",
                                  "reason":"Owner confirmed vegetable supplier purchase"
                                }
                                """.formatted(today)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.category").value("VEGETABLES"))
                .andExpect(jsonPath("$.vendor").value("Salman"))
                .andExpect(jsonPath("$.amount").value(500.00));

        assertThat(audits.count()).isEqualTo(1);
        assertThat(mappings.findFirstByRawTermIgnoreCaseAndCanonicalType("mystery supplies", "CATEGORY"))
                .isPresent()
                .get()
                .extracting(m -> m.getCanonicalValue())
                .isEqualTo("VEGETABLES");
        assertDashboardExpenses(today, 500.00);

        JsonNode learned = postManual("Paid Ramesh 300 for mystery supplies");
        assertThat(learned.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(learned.get("category").asText()).isEqualTo("VEGETABLES");
        assertThat(learned.get("vendor").asText()).isEqualTo("Ramesh");
        assertDashboardExpenses(today, 800.00);

        JsonNode rejectedCandidate = postManual("Paid Salman 100 for unapproved thing");
        assertThat(rejectedCandidate.get("status").asText()).isEqualTo("REVIEW_REQUIRED");
        UUID rejectedTransactionId = UUID.fromString(rejectedCandidate.get("transactionId").asText());
        String rejectedReviewId = reviewIdFor(rejectedTransactionId);

        mockMvc.perform(post("/api/reviews/{id}/reject", rejectedReviewId)
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not a valid business category\"}"))
                .andExpect(status().isOk());

        assertThat(transactions.findById(rejectedTransactionId)).isPresent()
                .get().extracting(t -> t.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(audits.count()).isEqualTo(2);
        assertDashboardExpenses(today, 800.00);
    }

    private JsonNode postManual(String text) throws Exception {
        var result = mockMvc.perform(post("/api/intake/manual-text")
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String reviewIdFor(UUID transactionId) throws Exception {
        var result = mockMvc.perform(get("/api/reviews")
                        .with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reviews = mapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode review : reviews) {
            if (transactionId.toString().equals(review.get("transactionId").asText())) {
                return review.get("id").asText();
            }
        }
        throw new AssertionError("Review item not found for transaction " + transactionId);
    }

    private void assertDashboardExpenses(LocalDate day, double expected) throws Exception {
        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", day.toString())
                        .param("to", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(expected));
    }
}
