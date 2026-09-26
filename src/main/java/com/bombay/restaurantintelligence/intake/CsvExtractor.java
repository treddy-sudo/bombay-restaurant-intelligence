package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import com.bombay.restaurantintelligence.repository.SourceColumnMappingRepository;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class CsvExtractor {
    private final SourceColumnMappingRepository columnMappings; public CsvExtractor(SourceColumnMappingRepository columnMappings){this.columnMappings=columnMappings;}
    public List<IntermediateBusinessRecord> extract(byte[] bytes,String filename,String checksum,String storageLocation){
        String sourceKey=(filename==null?"upload":filename.toLowerCase(Locale.ROOT).replaceFirst("\\.[^.]+$","")); Map<String,String> saved=new HashMap<>(); columnMappings.findBySourceKeyIgnoreCaseOrderBySourceColumn(sourceKey).forEach(m->saved.put(m.getSourceColumn().toLowerCase(Locale.ROOT),m.getCanonicalField()));
        try(Reader r=new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)){
            CSVFormat format=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).build();
            List<IntermediateBusinessRecord> out=new ArrayList<>(); int n=1;
            for(CSVRecord row:format.parse(r)){ n++; Map<String,String> f=new LinkedHashMap<>();
                for(var e:row.toMap().entrySet()){String h=e.getKey().toLowerCase(Locale.ROOT); String v=e.getValue(); if(v==null||v.isBlank())continue; String canonical=saved.get(h); if(canonical!=null){if("amount".equalsIgnoreCase(canonical))f.put("amount",TextExtractor.normalizeAmount(v));else f.put(canonical,v);continue;}
                    if(h.contains("amount")||h.contains("total")||h.contains("paid")) f.putIfAbsent("amount",TextExtractor.normalizeAmount(v));
                    else if(h.contains("vendor")||h.contains("supplier")) f.put("vendor",v);
                    else if(h.contains("employee")||h.contains("staff")) f.put("employee",v);
                    else if(h.contains("category")||h.contains("description")||h.contains("item")||h.contains("particular")){f.putIfAbsent("description",v);f.putIfAbsent("category",v);} else if(h.contains("date"))f.put("businessDate",v); else f.put(h.replace(' ','_'),v);
                }
                LocalDate d=parseDate(f.remove("businessDate")); String context=filename.toLowerCase(Locale.ROOT).contains("zomato")?"ZOMATO":filename.toLowerCase(Locale.ROOT).contains("swiggy")?"SWIGGY":""; f.put("context",context);
                out.add(new IntermediateBusinessRecord(SourceType.CSV,checksum+":"+n,d,null,f,new BigDecimal("0.92"),null,filename,storageLocation,checksum));
            } return out;
        }catch(IOException e){throw new IllegalArgumentException("Could not parse CSV",e);}
    }
    private static LocalDate parseDate(String v){if(v!=null)try{return LocalDate.parse(v);}catch(Exception ignored){} return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
}
