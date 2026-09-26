package com.bombay.restaurantintelligence.intake;

import java.util.List;

public interface AiExtractionProvider {
    List<IntermediateBusinessRecord> extract(byte[] content, String contentType, String filename, String sourceId, String sender);
}
