package com.bombay.restaurantintelligence.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaWhatsAppConfigurationValidatorTest {
    @Test
    void acceptsCompleteMetaConfiguration() {
        assertThatCode(() -> new MetaWhatsAppConfigurationValidator(
                "access-token", "phone-id", "business-id", "verify-token", "app-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsEveryMissingMetaCredential() {
        assertThatThrownBy(() -> new MetaWhatsAppConfigurationValidator("", " ", null, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WHATSAPP_ACCESS_TOKEN")
                .hasMessageContaining("WHATSAPP_PHONE_NUMBER_ID")
                .hasMessageContaining("WHATSAPP_BUSINESS_ACCOUNT_ID")
                .hasMessageContaining("WHATSAPP_VERIFY_TOKEN")
                .hasMessageContaining("WHATSAPP_APP_SECRET");
    }
}
