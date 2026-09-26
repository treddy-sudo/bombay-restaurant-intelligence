package com.bombay.restaurantintelligence.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name="app.ai.mode", havingValue="http")
public class HttpAiExtractionProvider implements AiExtractionProvider {
    private final WebClient client; private final ObjectMapper mapper; private final String url; private final String apiKey;
    public HttpAiExtractionProvider(WebClient.Builder builder, ObjectMapper mapper, @Value("${app.ai.url}") String url, @Value("${app.ai.api-key:}") String apiKey) {
        this.client=builder.build(); this.mapper=mapper; this.url=url; this.apiKey=apiKey;
    }
    @Override public List<IntermediateBusinessRecord> extract(byte[] content,String contentType,String filename,String sourceId,String sender) {
        try {
            String response=client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .headers(h->{ if(!apiKey.isBlank()) h.setBearerAuth(apiKey); })
                    .bodyValue(Map.of("contentType",contentType==null?"application/octet-stream":contentType,"filename",filename==null?"upload":filename,"base64", Base64.getEncoder().encodeToString(content),"sourceId",sourceId==null?"":sourceId,"sender",sender==null?"":sender))
                    .retrieve().bodyToMono(String.class).block();
            return mapper.readValue(response, new TypeReference<List<IntermediateBusinessRecord>>(){});
        } catch (Exception e) { throw new IllegalStateException("AI extraction provider failed", e); }
    }
}
