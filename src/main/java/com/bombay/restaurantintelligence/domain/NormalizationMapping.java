package com.bombay.restaurantintelligence.domain;
import jakarta.persistence.*; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="normalization_mappings") public class NormalizationMapping {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(name="raw_term",nullable=false) private String rawTerm;
 @Column(name="canonical_type",nullable=false) private String canonicalType;
 @Column(name="canonical_value",nullable=false) private String canonicalValue;
 @Column(nullable=false,precision=5,scale=4) private BigDecimal confidence=BigDecimal.ONE;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected NormalizationMapping(){} public NormalizationMapping(String raw,String type,String value,BigDecimal confidence){this.rawTerm=raw.toLowerCase().trim();this.canonicalType=type;this.canonicalValue=value;this.confidence=confidence;}
 public String getRawTerm(){return rawTerm;} public String getCanonicalType(){return canonicalType;} public String getCanonicalValue(){return canonicalValue;} public BigDecimal getConfidence(){return confidence;}
}
