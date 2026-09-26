package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.repository.SourceColumnMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SourceColumnMappingFlowTest {
    @Autowired MockMvc mockMvc;
    @Autowired SourceColumnMappingRepository mappings;

    @Test
    void ownerCanCreateReadAndCorrectSavedWorkbookMapping() throws Exception {
        mockMvc.perform(post("/api/source-column-mappings")
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKey":"purchase-report","sourceColumn":"Supplier Name","canonicalField":"vendor"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalField").value("vendor"));

        mockMvc.perform(post("/api/source-column-mappings")
                        .with(httpBasic("owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKey":"purchase-report","sourceColumn":"Supplier Name","canonicalField":"employee"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalField").value("employee"));

        mockMvc.perform(get("/api/source-column-mappings")
                        .with(httpBasic("owner", "test-password"))
                        .param("sourceKey", "purchase-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceColumn").value("Supplier Name"))
                .andExpect(jsonPath("$[0].canonicalField").value("employee"));

        var saved = mappings.findBySourceKeyIgnoreCaseOrderBySourceColumn("purchase-report");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getCanonicalField()).isEqualTo("employee");
    }
}
