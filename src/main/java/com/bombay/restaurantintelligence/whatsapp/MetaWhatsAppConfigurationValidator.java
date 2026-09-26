package com.bombay.restaurantintelligence.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.whatsapp.mode", havingValue = "meta")
public class MetaWhatsAppConfigurationValidator {
    public MetaWhatsAppConfigurationValidator(
            @Value("${app.whatsapp.access-token:}") String accessToken,
            @Value("${app.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${app.whatsapp.business-account-id:}") String businessAccountId,
            @Value("${app.whatsapp.verify-token:}") String verifyToken,
            @Value("${app.whatsapp.app-secret:}") String appSecret) {
        List<String> missing = new ArrayList<>();
        require(accessToken, "WHATSAPP_ACCESS_TOKEN", missing);
        require(phoneNumberId, "WHATSAPP_PHONE_NUMBER_ID", missing);
        require(businessAccountId, "WHATSAPP_BUSINESS_ACCOUNT_ID", missing);
        require(verifyToken, "WHATSAPP_VERIFY_TOKEN", missing);
        require(appSecret, "WHATSAPP_APP_SECRET", missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("WHATSAPP_MODE=meta requires: " + String.join(", ", missing));
        }
    }

    private static void require(String value, String environmentVariable, List<String> missing) {
        if (value == null || value.isBlank()) missing.add(environmentVariable);
    }
}
