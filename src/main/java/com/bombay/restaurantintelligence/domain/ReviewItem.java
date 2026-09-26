package com.bombay.restaurantintelligence.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="review_items") public class ReviewItem {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="transaction_id") private TransactionEntity transaction; @Column(nullable=false) private String reason; @Column(name="candidate_json",columnDefinition="TEXT") private String candidateJson; @Column(nullable=false) private String status="OPEN"; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now(); @Column(name="resolved_at") private Instant resolvedAt; @Column(name="resolution_note",columnDefinition="TEXT") private String resolutionNote;
 protected ReviewItem(){} public ReviewItem(TransactionEntity tx,String reason,String candidate){this.transaction=tx;this.reason=reason;this.candidateJson=candidate;}
 public UUID getId(){return id;} public TransactionEntity getTransaction(){return transaction;} public String getReason(){return reason;} public String getStatus(){return status;} public Instant getCreatedAt(){return createdAt;} public void resolve(String status,String note){this.status=status;this.resolutionNote=note;this.resolvedAt=Instant.now();}
}
