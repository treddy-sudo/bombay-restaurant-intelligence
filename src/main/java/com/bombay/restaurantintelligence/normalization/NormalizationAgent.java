package com.bombay.restaurantintelligence.normalization;

import com.bombay.restaurantintelligence.analytics.DailyMetricService;
import com.bombay.restaurantintelligence.domain.*;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class NormalizationAgent {
    private final TransactionRepository transactions; private final CategoryRepository categories; private final VendorRepository vendors; private final EmployeeRepository employees; private final NormalizationMappingRepository mappings; private final ReviewItemRepository reviews; private final DailyMetricService dailyMetrics; private final BigDecimal threshold;
    public NormalizationAgent(TransactionRepository transactions,CategoryRepository categories,VendorRepository vendors,EmployeeRepository employees,NormalizationMappingRepository mappings,ReviewItemRepository reviews,DailyMetricService dailyMetrics,@Value("${app.normalization.auto-post-threshold:0.85}") BigDecimal threshold){this.transactions=transactions;this.categories=categories;this.vendors=vendors;this.employees=employees;this.mappings=mappings;this.reviews=reviews;this.dailyMetrics=dailyMetrics;this.threshold=threshold;}

    @Transactional
    public NormalizationResult normalize(IntermediateBusinessRecord record){
        if(record.sourceId()!=null && transactions.existsBySourceMessageId(record.sourceId())) throw new DuplicateSourceException("Source already imported: "+record.sourceId());
        Map<String,String> f=record.fields(); String explicitCategory=Objects.toString(f.get("category"),"").trim(); String raw=String.join(" ", explicitCategory,Objects.toString(f.get("description"),""),Objects.toString(record.rawText(),"")).trim(); String context=Objects.toString(f.get("context"),"");
        CategoryResolution cr=resolveCategory(explicitCategory,raw,context);
        BigDecimal amount=parseAmount(f.get("amount"));
        Vendor vendor=resolveVendor(f.get("vendor")); Employee employee=resolveEmployee(f.get("employee"));
        TransactionType type=resolveType(f.get("transactionType"),cr.category(),vendor,employee,raw);
        BigDecimal normConfidence=record.confidence(); if(cr.confidence()!=null) normConfidence=normConfidence.min(cr.confidence());
        List<String> reasons=new ArrayList<>();
        if(amount.signum()<=0) reasons.add("Missing or invalid amount");
        if(cr.category()==null) reasons.add("Unknown category");
        if(normConfidence.compareTo(threshold)<0) reasons.add("Confidence below auto-post threshold");
        TransactionStatus status=reasons.isEmpty()?TransactionStatus.VERIFIED:TransactionStatus.REVIEW_REQUIRED;

        TransactionEntity tx=new TransactionEntity(); tx.setBusinessDate(record.businessDate()==null?LocalDate.now(ZoneId.of("Asia/Kolkata")):record.businessDate()); tx.setTransactionType(type); tx.setCategory(cr.category()); tx.setVendor(vendor); tx.setEmployee(employee); tx.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP)); tx.setDescription(f.getOrDefault("description",record.rawText())); tx.setSourceType(record.sourceType().name()); tx.setSourceReference(record.sourceId());
        if(record.sourceType().name().startsWith("WHATSAPP")) tx.setSourceMessageId(record.sourceId());
        tx.setSourceFilename(record.sourceFilename()); tx.setSender(record.sender()); tx.setReceivedAt(Instant.now()); tx.setRawText(record.rawText()); tx.setOriginalFileLocation(record.originalFileLocation()); tx.setFileChecksum(record.fileChecksum()); tx.setExtractionConfidence(record.confidence()); tx.setNormalizationConfidence(normConfidence); tx.setStatus(status);
        String fingerprint=fingerprint(record,tx,cr.category(),vendor); if(fingerprint!=null){if(transactions.existsByNormalizedFingerprint(fingerprint))throw new DuplicateSourceException("Normalized source row already imported");tx.setNormalizedFingerprint(fingerprint);}
        try { transactions.saveAndFlush(tx); } catch(DataIntegrityViolationException e){ throw new DuplicateSourceException("Duplicate source detected"); }
        if(status==TransactionStatus.REVIEW_REQUIRED) reviews.save(new ReviewItem(tx,String.join("; ",reasons),raw));
        if(status==TransactionStatus.VERIFIED) dailyMetrics.refresh(tx.getBusinessDate());
        return result(tx,reasons.isEmpty()?"Recorded successfully":"Sent to review: "+String.join("; ",reasons));
    }

    private CategoryResolution resolveCategory(String explicitCategory,String raw,String context){
        String text=raw==null?"":raw.toLowerCase(Locale.ROOT);
        if(explicitCategory!=null&&!explicitCategory.isBlank()){
            Optional<Category> direct=categories.findByCodeIgnoreCase(explicitCategory.trim().replace(' ','_')); if(direct.isPresent()) return new CategoryResolution(direct.get(),BigDecimal.ONE);
        }
        List<NormalizationMapping> all=mappings.findByCanonicalType("CATEGORY");
        return all.stream().filter(m->text.contains(m.getRawTerm().toLowerCase(Locale.ROOT))).filter(m->contextAllowed(m,text,context)).sorted(Comparator.comparingInt((NormalizationMapping m)->m.getRawTerm().length()).reversed()).findFirst().flatMap(m->categories.findByCodeIgnoreCase(m.getCanonicalValue()).map(c->new CategoryResolution(c,m.getConfidence()))).orElseGet(()->heuristicCategory(text,context));
    }
    private boolean contextAllowed(NormalizationMapping m,String text,String context){ if(m.getCanonicalValue().startsWith("ZOMATO_") && !text.contains("zomato") && !"ZOMATO".equalsIgnoreCase(context))return false; if(m.getCanonicalValue().startsWith("SWIGGY_") && !text.contains("swiggy") && !"SWIGGY".equalsIgnoreCase(context))return false; return true; }
    private CategoryResolution heuristicCategory(String text,String context){
        Map<String,String> keys=new LinkedHashMap<>(); keys.put("rent","RENT");keys.put("electric","ELECTRICITY");keys.put("salary","EMPLOYEE_SALARY");keys.put("packaging","PACKAGING");keys.put("dairy","DAIRY");keys.put("milk","DAIRY");keys.put("grocery","GROCERIES");keys.put("masala","MASALA");keys.put("oil","OIL");keys.put("gas","GAS");keys.put("water","WATER");keys.put("bakery","BAKERY");keys.put("maintenance","MAINTENANCE");keys.put("cash sale","CASH_SALES");keys.put("upi sale","UPI_SALES");keys.put("zomato sale","ZOMATO_SALES");keys.put("swiggy sale","SWIGGY_SALES");
        for(var e:keys.entrySet())if(text.contains(e.getKey())){var c=categories.findByCodeIgnoreCase(e.getValue());if(c.isPresent())return new CategoryResolution(c.get(),new BigDecimal("0.90"));}
        if("SWIGGY".equalsIgnoreCase(context)&&text.matches(".*(advert|promotion|investment in growth).*")){var c=categories.findByCodeIgnoreCase("SWIGGY_ADVERTISING");if(c.isPresent())return new CategoryResolution(c.get(),new BigDecimal("0.88"));}
        return new CategoryResolution(null,new BigDecimal("0.50"));
    }
    private TransactionType resolveType(String explicit,Category c,Vendor v,Employee e,String raw){ if(explicit!=null)try{return TransactionType.valueOf(explicit.trim().toUpperCase(Locale.ROOT));}catch(Exception ignored){} if(c!=null){String code=c.getCode(); if(code.endsWith("_SALES")||c.getGroupName().equals("SALES"))return TransactionType.SALE; if(code.equals("EMPLOYEE_ADVANCE"))return TransactionType.EMPLOYEE_ADVANCE; if(code.equals("EMPLOYEE_SALARY"))return TransactionType.SALARY; if(c.getGroupName().equals("ADVERTISING"))return TransactionType.ADVERTISING;} if(v!=null)return TransactionType.VENDOR_PAYMENT; if(e!=null&&raw.toLowerCase(Locale.ROOT).contains("advance"))return TransactionType.EMPLOYEE_ADVANCE; return TransactionType.EXPENSE; }
    private Vendor resolveVendor(String name){ if(name==null||name.isBlank())return null;String n=name.trim().toLowerCase(Locale.ROOT);return vendors.findByNormalizedName(n).orElseGet(()->vendors.save(new Vendor(name))); }
    private Employee resolveEmployee(String name){ if(name==null||name.isBlank())return null;String n=name.trim().toLowerCase(Locale.ROOT);return employees.findByNormalizedName(n).orElseGet(()->employees.save(new Employee(name))); }
    private static BigDecimal parseAmount(String v){if(v==null||v.isBlank())return BigDecimal.ZERO;try{return new BigDecimal(v.replace("₹","").replace(",","").trim());}catch(Exception e){return BigDecimal.ZERO;}}
    private static String fingerprint(IntermediateBusinessRecord r,TransactionEntity tx,Category c,Vendor v){if(r.sourceId()==null||r.sourceId().isBlank())return null;String s=r.sourceType()+"|"+r.sourceId()+"|"+tx.getBusinessDate()+"|"+tx.getAmount()+"|"+(c==null?"":c.getCode())+"|"+(v==null?"":v.getNormalizedName());try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static NormalizationResult result(TransactionEntity tx,String message){return new NormalizationResult(tx.getId(),tx.getStatus().name(),tx.getTransactionType().name(),tx.getCategory()==null?null:tx.getCategory().getCode(),tx.getVendor()==null?null:tx.getVendor().getName(),tx.getEmployee()==null?null:tx.getEmployee().getName(),tx.getAmount(),tx.getCurrency(),tx.getBusinessDate(),message);}
    private record CategoryResolution(Category category,BigDecimal confidence){}
}
