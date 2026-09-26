package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import com.bombay.restaurantintelligence.normalization.NormalizationAgent;
import com.bombay.restaurantintelligence.normalization.NormalizationResult;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class IntakeAgent {
    private final TextExtractor textExtractor; private final NormalizationAgent normalizationAgent;
    public IntakeAgent(TextExtractor textExtractor,NormalizationAgent normalizationAgent){this.textExtractor=textExtractor;this.normalizationAgent=normalizationAgent;}
    public NormalizationResult ingestManualText(String text,String sender){return normalizationAgent.normalize(textExtractor.extract(text, SourceType.MANUAL_TEXT, UUID.randomUUID().toString(),sender));}
    public NormalizationResult ingestWhatsAppText(String text,String sourceId,String sender){return normalizationAgent.normalize(textExtractor.extract(text, SourceType.WHATSAPP_TEXT,sourceId,sender));}
    public NormalizationResult ingest(IntermediateBusinessRecord record){return normalizationAgent.normalize(record);}
}
