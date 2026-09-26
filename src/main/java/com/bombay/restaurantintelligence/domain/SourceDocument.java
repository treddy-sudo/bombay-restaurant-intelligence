package com.bombay.restaurantintelligence.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="source_documents") public class SourceDocument {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @Column(nullable=false) private String filename; @Column(name="content_type") private String contentType;
 @Column(name="file_checksum",nullable=false,unique=true,length=64) private String fileChecksum; @Column(name="storage_location") private String storageLocation; @Column(name="received_at",nullable=false) private Instant receivedAt; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 protected SourceDocument(){} public SourceDocument(String filename,String contentType,String checksum,String location,Instant received){this.filename=filename;this.contentType=contentType;this.fileChecksum=checksum;this.storageLocation=location;this.receivedAt=received;}
 public String getFileChecksum(){return fileChecksum;} public String getStorageLocation(){return storageLocation;}
}
