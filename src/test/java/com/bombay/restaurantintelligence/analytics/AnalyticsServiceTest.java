package com.bombay.restaurantintelligence.analytics;

import com.bombay.restaurantintelligence.domain.*;
import com.bombay.restaurantintelligence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {
    @Mock TransactionRepository repo;
    AnalyticsService service;
    @BeforeEach void setup(){service=new AnalyticsService(repo);}
    @Test void totalsAreDeterministicAndVerifiedOnlySource(){LocalDate d=LocalDate.of(2026,9,25);var sale=tx(d,TransactionType.SALE,"IN_STORE_SALES",null,"10000");var vendor=tx(d,TransactionType.VENDOR_PAYMENT,"VEGETABLES","Salman","2500");when(repo.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(eq(TransactionStatus.VERIFIED),any(),any())).thenAnswer(i->{LocalDate f=i.getArgument(1),t=i.getArgument(2);return !d.isBefore(f)&&!d.isAfter(t)?List.of(sale,vendor):List.of();});when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.of(sale));var data=service.dashboard(d,d);assertThat(data.selected().sales()).isEqualByComparingTo("10000.00");assertThat(data.selected().expenses()).isEqualByComparingTo("2500.00");assertThat(data.selected().netOperatingResult()).isEqualByComparingTo("7500.00");assertThat(data.vendorSpending().getFirst().name()).isEqualTo("Salman");assertThat(data.categoryExpenses().getFirst().name()).isEqualTo("VEGETABLES");}
    @Test void dateRangeAndMonthlyTotalsUseRequestedVerifiedWindow(){LocalDate first=LocalDate.of(2026,9,1),second=LocalDate.of(2026,9,25);var a=tx(first,TransactionType.SALE,"IN_STORE_SALES",null,"1000");var b=tx(second,TransactionType.SALE,"ZOMATO_SALES",null,"3000");when(repo.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(eq(TransactionStatus.VERIFIED),any(),any())).thenAnswer(i->{LocalDate from=i.getArgument(1),to=i.getArgument(2);return List.of(a,b).stream().filter(t->!t.getBusinessDate().isBefore(from)&&!t.getBusinessDate().isAfter(to)).toList();});when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.of(a));var selected=service.dashboard(second,second);assertThat(selected.selected().sales()).isEqualByComparingTo("3000.00");assertThat(selected.monthToDate().sales()).isGreaterThanOrEqualTo(new BigDecimal("3000.00"));}
    @Test void comparisonDoesNotInventWhenPreviousMissing(){LocalDate d=LocalDate.of(2026,9,25);when(repo.findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(eq(TransactionStatus.VERIFIED),any(),any())).thenReturn(List.of());when(repo.findFirstByStatusOrderByBusinessDateAsc(TransactionStatus.VERIFIED)).thenReturn(Optional.empty());assertThat(service.dashboard(d,d).comparison().message()).isEqualTo("Not enough data for comparison");}
    private static TransactionEntity tx(LocalDate d,TransactionType type,String category,String vendor,String amount){TransactionEntity t=new TransactionEntity();t.setBusinessDate(d);t.setTransactionType(type);String group=category.endsWith("_SALES")?"SALES":"PURCHASES";t.setCategory(new Category(category,category,group));if(vendor!=null)t.setVendor(new Vendor(vendor));t.setAmount(new BigDecimal(amount));t.setStatus(TransactionStatus.VERIFIED);t.setSourceType("TEST");return t;}
}
