package com.bombay.restaurantintelligence.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="source_messages") public class SourceMessage {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="source_message_id",nullable=false,unique=true) private String sourceMessageId;
 private String sender; @Column(name="message_type",nullable=false) private String messageType; @Column(name="raw_text",columnDefinition="TEXT") private String rawText;
 @Column(name="received_at",nullable=false) private Instant receivedAt; @Column(name="payload_json",columnDefinition="TEXT") private String payloadJson; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 protected SourceMessage(){} public SourceMessage(String id,String sender,String type,String raw,Instant received,String payload){this.sourceMessageId=id;this.sender=sender;this.messageType=type;this.rawText=raw;this.receivedAt=received;this.payloadJson=payload;}
 public String getSourceMessageId(){return sourceMessageId;}
}
