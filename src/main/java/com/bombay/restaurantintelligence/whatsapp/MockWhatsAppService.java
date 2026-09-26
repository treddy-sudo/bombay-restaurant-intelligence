package com.bombay.restaurantintelligence.whatsapp;
import org.slf4j.Logger; import org.slf4j.LoggerFactory; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.stereotype.Service;
@Service @ConditionalOnProperty(name="app.whatsapp.mode",havingValue="mock",matchIfMissing=true)
public class MockWhatsAppService implements WhatsAppService {
 private static final Logger log=LoggerFactory.getLogger(MockWhatsAppService.class);
 @Override public void sendText(String recipient,String text){log.info("Mock WhatsApp reply to {}: {}",recipient,text);}
 @Override public MediaPayload downloadMedia(String mediaId,String fallbackFilename){return new MediaPayload(new byte[0],"application/octet-stream",fallbackFilename==null?"mock-media":fallbackFilename);}
}
