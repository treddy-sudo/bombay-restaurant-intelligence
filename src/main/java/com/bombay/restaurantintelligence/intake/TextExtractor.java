package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TextExtractor {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern PAID = Pattern.compile("(?i)\\bpaid\\s+([\\p{L}][\\p{L} .'-]{0,80}?)\\s+(?:₹\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:for\\s+)?(.+)?$");
    private static final Pattern AMOUNT = Pattern.compile("(?:₹\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern ADVANCE_TO = Pattern.compile("(?i)\\b(?:staff|employee|salary)?\\s*advance(?:\\s+to)?\\s+([\\p{L}][\\p{L} .'-]{0,80}?)\\s+(?:₹\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    public IntermediateBusinessRecord extract(String text, SourceType sourceType, String sourceId, String sender) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Text is required");
        String clean = text.trim();
        Map<String,String> fields = new LinkedHashMap<>();
        BigDecimal confidence = new BigDecimal("0.72");

        Matcher paid = PAID.matcher(clean);
        if (paid.find()) {
            fields.put("vendor", paid.group(1).trim());
            fields.put("amount", normalizeAmount(paid.group(2)));
            String description = paid.group(3) == null ? "" : paid.group(3).trim();
            fields.put("description", description);
            confidence = new BigDecimal("0.98");
        } else {
            Matcher advance = ADVANCE_TO.matcher(clean);
            if (advance.find()) {
                fields.put("employee", advance.group(1).trim());
                fields.put("amount", normalizeAmount(advance.group(2)));
                fields.put("description", "employee advance");
                confidence = new BigDecimal("0.96");
            } else {
                Matcher amount = AMOUNT.matcher(clean);
                if (amount.find()) fields.put("amount", normalizeAmount(amount.group(1)));
                fields.put("description", clean);
            }
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("zomato")) fields.put("context", "ZOMATO");
        if (lower.contains("swiggy")) fields.put("context", "SWIGGY");
        return new IntermediateBusinessRecord(sourceType, sourceId, LocalDate.now(BUSINESS_ZONE), sender, fields, confidence, clean, null, null, null);
    }

    public static String normalizeAmount(String raw) {
        String normalized = raw.replace("₹", "").replace(",", "").trim();
        return new BigDecimal(normalized).setScale(2).toPlainString();
    }
}
