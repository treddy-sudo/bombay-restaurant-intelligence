package com.bombay.restaurantintelligence.whatsapp;
public interface WhatsAppService {
    void sendText(String recipient,String text);
    MediaPayload downloadMedia(String mediaId,String fallbackFilename);
    record MediaPayload(byte[] bytes,String contentType,String filename){}
}
