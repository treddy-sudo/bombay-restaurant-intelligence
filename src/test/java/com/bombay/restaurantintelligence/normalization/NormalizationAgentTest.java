package com.bombay.restaurantintelligence.normalization;

import com.bombay.restaurantintelligence.domain.*;
import com.bombay.restaurantintelligence.intake.IntermediateBusinessRecord;
import com.bombay.restaurantintelligence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NormalizationAgentTest {
    @Mock TransactionRepository transactions; @Mock CategoryRepository categories; @Mock VendorRepository vendors; @Mock EmployeeRepository employees; @Mock NormalizationMappingRepository mappings; @Mock ReviewItemRepository reviews;
    NormalizationAgent agent;
    @BeforeEach void setup(){agent=new NormalizationAgent(transactions,categories,vendors,employees,mappings,reviews,new BigDecimal("0.85"));}
    @Test void vegetableVendorPaymentAutoPosts(){basePersistence();Category veg=new Category("VEGETABLES","Vegetables","PURCHASES");when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of(new NormalizationMapping("vegetables","CATEGORY","VEGETABLES",BigDecimal.ONE)));stubCategory("VEGETABLES",veg);when(vendors.findByNormalizedName("salman")).thenReturn(Optional.empty());when(vendors.save(any())).thenAnswer(i->i.getArgument(0));var r=agent.normalize(rec(Map.of("vendor","Salman","amount","6500.00","description","vegetables"),new BigDecimal("0.98"),"m1"));assertThat(r.status()).isEqualTo("VERIFIED");assertThat(r.transactionType()).isEqualTo("VENDOR_PAYMENT");assertThat(r.category()).isEqualTo("VEGETABLES");assertThat(r.amount()).isEqualByComparingTo("6500.00");}
    @Test void sabjiMapsToVegetables(){basePersistence();Category veg=new Category("VEGETABLES","Vegetables","PURCHASES");when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of(new NormalizationMapping("sabji","CATEGORY","VEGETABLES",BigDecimal.ONE)));stubCategory("VEGETABLES",veg);var r=agent.normalize(rec(Map.of("amount","1000","description","sabji"),new BigDecimal("0.95"),"m2"));assertThat(r.category()).isEqualTo("VEGETABLES");assertThat(r.status()).isEqualTo("VERIFIED");}
    @Test void employeeAdvanceNormalizes(){basePersistence();Category advance=new Category("EMPLOYEE_ADVANCE","Employee advance","EMPLOYEES");when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of(new NormalizationMapping("employee advance","CATEGORY","EMPLOYEE_ADVANCE",BigDecimal.ONE)));stubCategory("EMPLOYEE_ADVANCE",advance);when(employees.findByNormalizedName("ravi")).thenReturn(Optional.empty());when(employees.save(any())).thenAnswer(i->i.getArgument(0));var r=agent.normalize(rec(Map.of("employee","Ravi","amount","1500","description","employee advance"),new BigDecimal("0.96"),"m3"));assertThat(r.transactionType()).isEqualTo("EMPLOYEE_ADVANCE");assertThat(r.status()).isEqualTo("VERIFIED");}
    @Test void zomatoInvestmentInGrowthNeedsContextAndMaps(){basePersistence();Category ads=new Category("ZOMATO_ADVERTISING","Zomato advertising","ADVERTISING");when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of(new NormalizationMapping("investment in growth","CATEGORY","ZOMATO_ADVERTISING",new BigDecimal("0.95"))));stubCategory("ZOMATO_ADVERTISING",ads);var r=agent.normalize(rec(Map.of("amount","2000","description","Investment in Growth","context","ZOMATO"),new BigDecimal("0.96"),"m4"));assertThat(r.category()).isEqualTo("ZOMATO_ADVERTISING");assertThat(r.transactionType()).isEqualTo("ADVERTISING");}
    @Test void unknownCategoryGoesToReview(){basePersistence();when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of());var r=agent.normalize(rec(Map.of("amount","500","description","mystery thing"),new BigDecimal("0.95"),"m5"));assertThat(r.status()).isEqualTo("REVIEW_REQUIRED");verify(reviews).save(any());}
    @Test void lowConfidenceGoesToReview(){basePersistence();Category veg=new Category("VEGETABLES","Vegetables","PURCHASES");when(mappings.findByCanonicalType("CATEGORY")).thenReturn(List.of(new NormalizationMapping("vegetables","CATEGORY","VEGETABLES",BigDecimal.ONE)));stubCategory("VEGETABLES",veg);var r=agent.normalize(rec(Map.of("amount","8700","description","vegetables"),new BigDecimal("0.40"),"m6"));assertThat(r.status()).isEqualTo("REVIEW_REQUIRED");}
    @Test void duplicateSourceRejected(){when(transactions.existsBySourceMessageId("wamid.1")).thenReturn(true);assertThatThrownBy(()->agent.normalize(rec(Map.of("amount","1"),BigDecimal.ONE,"wamid.1"))).isInstanceOf(DuplicateSourceException.class);}
    private void basePersistence(){when(transactions.existsBySourceMessageId(anyString())).thenReturn(false);when(transactions.existsByNormalizedFingerprint(anyString())).thenReturn(false);when(transactions.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));}
    private void stubCategory(String code,Category category){when(categories.findByCodeIgnoreCase(anyString())).thenAnswer(i->code.equalsIgnoreCase(i.getArgument(0))?Optional.of(category):Optional.empty());}
    private static IntermediateBusinessRecord rec(Map<String,String> f,BigDecimal c,String id){return new IntermediateBusinessRecord(SourceType.WHATSAPP_TEXT,id,LocalDate.of(2026,9,25),"manager",f,c,String.join(" ",f.values()),null,null,null);}
}
