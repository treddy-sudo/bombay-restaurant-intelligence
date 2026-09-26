package com.bombay.restaurantintelligence.config;

import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.IngestionJobRepository;
import com.bombay.restaurantintelligence.web.ReferenceController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReferenceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.owner.username=owner",
        "app.owner.password=test-password"
})
class SecurityConfigTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    CategoryRepository categories;

    @MockBean
    IngestionJobRepository jobs;

    @Test
    void unauthorizedApiDoesNotTriggerBrowserBasicAuthDialog() throws Exception {
        mockMvc.perform(get("/api/auth/check"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void validBasicCredentialsStillAuthenticate() throws Exception {
        mockMvc.perform(get("/api/auth/check").with(httpBasic("owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }
}
