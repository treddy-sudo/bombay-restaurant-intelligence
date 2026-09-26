package com.bombay.restaurantintelligence.review;

import com.bombay.restaurantintelligence.domain.*; import com.bombay.restaurantintelligence.normalization.NormalizationAgent; import com.bombay.restaurantintelligence.normalization.NormalizationResult; import com.bombay.restaurantintelligence.repository.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.*;

@Service public class ReviewService {
 private final ReviewItemRepository reviews; private final CategoryRepository categories; private final VendorRepository vendors; private final TransactionRepository transactions; private final AuditLogRepository audits; private final NormalizationMappingRepository mappings;
 public ReviewService(ReviewItemRepository reviews,CategoryRepository categories,VendorRepository vendors,TransactionRepository transactions,AuditLogRepository audits,NormalizationMappingRepository mappings){this.reviews=reviews;this.categories=categories;this.vendors=vendors;this.transactions=transactions;this.audits=audits;this.mappings=mappings;}
 public List<ReviewView> open(){return reviews.findTop50ByStatusOrderByCreatedAtDesc("OPEN").stream().map(ReviewView::of).toList();}
 @Transactional public NormalizationResult approve(UUID id,CorrectionRequest c,String changedBy){ReviewItem item=reviews.findById(id).orElseThrow();TransactionEntity tx=item.getTransaction();String old=snapshot(tx);
   if(c.categoryCode()!=null&&!c.categoryCode().isBlank())tx.setCategory(categories.findByCodeIgnoreCase(c.categoryCode()).orElseThrow(()->new IllegalArgumentException("Unknown category")));
   if(c.vendor()!=null&&!c.vendor().isBlank()){String n=c.vendor().trim().toLowerCase(Locale.ROOT);tx.setVendor(vendors.findByNormalizedName(n).orElseGet(()->vendors.save(new Vendor(c.vendor()))));}
   if(c.amount()!=null&&c.amount().signum()>0)tx.setAmount(c.amount()); if(c.businessDate()!=null)tx.setBusinessDate(c.businessDate()); tx.setStatus(TransactionStatus.VERIFIED); transactions.save(tx); item.resolve("APPROVED",c.reason()); reviews.save(item);
   if(c.rawTerm()!=null&&!c.rawTerm().isBlank()&&tx.getCategory()!=null){mappings.findFirstByRawTermIgnoreCaseAndCanonicalType(c.rawTerm(),"CATEGORY").orElseGet(()->mappings.save(new NormalizationMapping(c.rawTerm(),"CATEGORY",tx.getCategory().getCode(),BigDecimal.ONE)));}
   audits.save(new AuditLog("TRANSACTION",tx.getId(),old,snapshot(tx),changedBy,c.reason())); return NormalizationAgent.result(tx,"Review approved"); }
 @Transactional public void reject(UUID id,String reason,String changedBy){ReviewItem item=reviews.findById(id).orElseThrow();TransactionEntity tx=item.getTransaction();String old=snapshot(tx);tx.setStatus(TransactionStatus.REJECTED);transactions.save(tx);item.resolve("REJECTED",reason);reviews.save(item);audits.save(new AuditLog("TRANSACTION",tx.getId(),old,snapshot(tx),changedBy,reason));}
 private static String snapshot(TransactionEntity t){return "status="+t.getStatus()+",amount="+t.getAmount()+",category="+(t.getCategory()==null?null:t.getCategory().getCode())+",vendor="+(t.getVendor()==null?null:t.getVendor().getName());}
 public record CorrectionRequest(String categoryCode,String vendor,BigDecimal amount,LocalDate businessDate,String rawTerm,String reason){}
 public record ReviewView(UUID id,UUID transactionId,String reason,String status,LocalDate businessDate,BigDecimal amount,String rawText,String category,String vendor){static ReviewView of(ReviewItem r){TransactionEntity t=r.getTransaction();return new ReviewView(r.getId(),t.getId(),r.getReason(),r.getStatus(),t.getBusinessDate(),t.getAmount(),t.getRawText(),t.getCategory()==null?null:t.getCategory().getCode(),t.getVendor()==null?null:t.getVendor().getName());}}
}
