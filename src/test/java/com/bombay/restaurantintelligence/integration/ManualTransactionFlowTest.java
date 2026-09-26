package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.NormalizationMapping;
import com.bombay.restaurantintelligence.domain.TransactionStatus;
import com.bombay.restaurantintelligence.domain.TransactionType;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.NormalizationMappingRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManualTransactionFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired CategoryRepository categories;
    @Autowired NormalizationMappingRepository mappings;
    @Autowired TransactionRepository transactions;

    @BeforeEach
    void seedNormalization() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        mappings.save(new NormalizationMapping("vegetables", "CATEGORY", "VEGETABLES", BigDecimal.ONE));
    }

    @Test
    void manualVendorPaymentPersistsAndUpdatesDashboard() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        mockMvc.perform(post("/api/intake/manual-text")
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Paid Salman 6500 for vegetables\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.transactionType").value("VENDOR_PAYMENT"))
                .andExpect(jsonPath("$.category").value("VEGETABLES"))
                .andExpect(jsonPath("$.vendor").value("Salman"))
                .andExpect(jsonPath("$.amount").value(6500.00));

        var stored = transactions.findTop50ByOrderByCreatedAtDesc();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.VERIFIED);
        assertThat(stored.getFirst().getTransactionType()).isEqualTo(TransactionType.VENDOR_PAYMENT);
        assertThat(stored.getFirst().getAmount()).isEqualByComparingTo("6500.00");
        assertThat(stored.getFirst().getVendor().getName()).isEqualTo("Salman");
        assertThat(stored.getFirst().getCategory().getCode()).isEqualTo("VEGETABLES");

        mockMvc.perform(get("/api/analytics/dashboard")
                        .with(httpBasic("owner", "test-password"))
                        .param("from", today)
                        .param("to", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.expenses").value(6500.00))
                .andExpect(jsonPath("$.selected.vendorPayments").value(6500.00))
                .andExpect(jsonPath("$.categoryExpenses[0].name").value("VEGETABLES"))
                .andExpect(jsonPath("$.categoryExpenses[0].amount").value(6500.00))
                .andExpect(jsonPath("$.vendorSpending[0].name").value("Salman"))
                .andExpect(jsonPath("$.vendorSpending[0].amount").value(6500.00));
    }
}
