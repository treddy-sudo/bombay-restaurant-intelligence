package com.bombay.restaurantintelligence.integration;

import com.bombay.restaurantintelligence.domain.Category;
import com.bombay.restaurantintelligence.domain.DailyMetric;
import com.bombay.restaurantintelligence.domain.NormalizationMapping;
import com.bombay.restaurantintelligence.intake.IntakeAgent;
import com.bombay.restaurantintelligence.repository.CategoryRepository;
import com.bombay.restaurantintelligence.repository.DailyMetricRepository;
import com.bombay.restaurantintelligence.repository.NormalizationMappingRepository;
import com.bombay.restaurantintelligence.repository.ReviewItemRepository;
import com.bombay.restaurantintelligence.review.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DailyMetricFlowTest {
    @Autowired IntakeAgent intake;
    @Autowired DailyMetricRepository metrics;
    @Autowired ReviewItemRepository reviews;
    @Autowired ReviewService reviewService;
    @Autowired CategoryRepository categories;
    @Autowired NormalizationMappingRepository mappings;

    @BeforeEach
    void seedNormalization() {
        categories.save(new Category("VEGETABLES", "Vegetables", "PURCHASES"));
        mappings.save(new NormalizationMapping("vegetables", "CATEGORY", "VEGETABLES", BigDecimal.ONE));
    }

    @Test
    void verifiedOnlyTransactionsMaterializeAndReviewApprovalRefreshesTotals() {
        var verified = intake.ingestManualText("Paid Salman 6500 for vegetables", "owner");
        assertThat(verified.status()).isEqualTo("VERIFIED");

        var date = verified.businessDate();
        assertMetric(date, "EXPENSES", "6500.00");
        assertMetric(date, "VENDOR_PAYMENTS", "6500.00");
        assertMetric(date, "SALES", "0.00");

        var pending = intake.ingestManualText("Paid Salman 100 for mystery supplies", "owner");
        assertThat(pending.status()).isEqualTo("REVIEW_REQUIRED");
        assertMetric(date, "EXPENSES", "6500.00");
        assertMetric(date, "VENDOR_PAYMENTS", "6500.00");

        var review = reviews.findTop50ByStatusOrderByCreatedAtDesc("OPEN").getFirst();
        reviewService.approve(review.getId(), new ReviewService.CorrectionRequest(
                "VEGETABLES", null, null, null, "mystery supplies", "owner approved"), "owner");

        assertMetric(date, "EXPENSES", "6600.00");
        assertMetric(date, "VENDOR_PAYMENTS", "6600.00");
        assertMetric(date, "NET_OPERATING_RESULT", "-6600.00");
    }

    private void assertMetric(java.time.LocalDate date, String key, String expected) {
        var metric = metrics.findById(new DailyMetric.Id(date, key)).orElseThrow();
        assertThat(metric.getAmount()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
