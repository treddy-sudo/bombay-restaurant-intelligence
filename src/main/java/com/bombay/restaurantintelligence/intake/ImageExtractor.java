package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ImageExtractor {
    private final AiExtractionProvider ai;

    public ImageExtractor(AiExtractionProvider ai) {
        this.ai = ai;
    }

    public List<IntermediateBusinessRecord> extract(byte[] bytes,
                                                     String contentType,
                                                     String filename,
                                                     String sourceId,
                                                     String sender,
                                                     SourceType sourceType,
                                                     String storageLocation,
                                                     String checksum) {
        List<IntermediateBusinessRecord> extracted = ai.extract(
                bytes, contentType, filename, sourceId, sender);
        List<IntermediateBusinessRecord> records = new ArrayList<>(extracted.size());
        for (int i = 0; i < extracted.size(); i++) {
            IntermediateBusinessRecord candidate = extracted.get(i);
            String stableId = sourceId == null || sourceId.isBlank()
                    ? checksum + ":" + (i + 1)
                    : sourceId + ":" + (i + 1);
            records.add(new IntermediateBusinessRecord(
                    sourceType,
                    stableId,
                    candidate.businessDate(),
                    sender == null ? candidate.sender() : sender,
                    candidate.fields(),
                    candidate.confidence(),
                    candidate.rawText(),
                    filename,
                    storageLocation,
                    checksum));
        }
        return records;
    }
}
