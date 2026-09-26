package com.bombay.restaurantintelligence.intake;

import com.bombay.restaurantintelligence.domain.SourceType;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import com.bombay.restaurantintelligence.repository.SourceColumnMappingRepository;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
public class ExcelExtractor {
    private final SourceColumnMappingRepository columnMappings;
    public ExcelExtractor(SourceColumnMappingRepository columnMappings){this.columnMappings=columnMappings;}
    public List<IntermediateBusinessRecord> extract(byte[] bytes, String filename, String checksum, String storageLocation) {
        List<IntermediateBusinessRecord> out=new ArrayList<>();
        String sourceKey=sourceKey(filename); Map<String,String> saved=new HashMap<>(); columnMappings.findBySourceKeyIgnoreCaseOrderBySourceColumn(sourceKey).forEach(m->saved.put(m.getSourceColumn().toLowerCase(Locale.ROOT),m.getCanonicalField()));
        try (Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter=new DataFormatter();
            for (Sheet sheet: workbook) {
                Iterator<Row> rows=sheet.rowIterator(); if(!rows.hasNext()) continue;
                Row header=rows.next(); Map<Integer,String> headers=new LinkedHashMap<>();
                for(Cell c:header) headers.put(c.getColumnIndex(),normalizeHeader(formatter.formatCellValue(c)));
                int rowNo=1;
                while(rows.hasNext()) {
                    Row row=rows.next(); rowNo++; Map<String,String> fields=new LinkedHashMap<>(); boolean any=false;
                    for(var e:headers.entrySet()) {
                        Cell cell=row.getCell(e.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL); if(cell==null) continue;
                        String v=formatter.formatCellValue(cell).trim(); if(v.isBlank()) continue; any=true; String canonical=saved.get(e.getValue().toLowerCase(Locale.ROOT)); if(canonical!=null) putCanonical(fields,canonical,v); else mapField(fields,e.getValue(),v);
                    }
                    if(!any) continue;
                    LocalDate date=parseDate(fields.remove("businessDate"));
                    fields.put("context", detectContext(filename+" "+sheet.getSheetName()));
                    out.add(new IntermediateBusinessRecord(SourceType.EXCEL, checksum+":"+sheet.getSheetName()+":"+rowNo,date,null,fields,new BigDecimal("0.92"),null,filename,storageLocation,checksum));
                }
            }
            return out;
        } catch(Exception e){ throw new IllegalArgumentException("Could not parse Excel workbook: "+e.getMessage(),e); }
    }
    private static String normalizeHeader(String h){return h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();}
    private static String sourceKey(String filename){String n=filename==null?"upload":filename.toLowerCase(Locale.ROOT);return n.replaceFirst("\\.[^.]+$","");}
    private static void putCanonical(Map<String,String> f,String canonical,String v){String key=canonical.trim(); if("amount".equalsIgnoreCase(key))f.put("amount",TextExtractor.normalizeAmount(v));else f.put(key,v);}
    private static void mapField(Map<String,String> f,String h,String v){
        if(h.matches(".*(amount|total|value|paid).*")) f.putIfAbsent("amount",TextExtractor.normalizeAmount(v));
        else if(h.matches(".*(vendor|supplier).*")) f.put("vendor",v);
        else if(h.matches(".*(employee|staff).*")) f.put("employee",v);
        else if(h.matches(".*(category|expense|particular|item|description|label).*")) {f.putIfAbsent("description",v); f.putIfAbsent("category",v);}
        else if(h.matches(".*(date|business date).*")) f.put("businessDate",v);
        else if(h.matches(".*(type|transaction).*")) f.put("transactionType",v);
        else f.put(h.replace(" ","_"),v);
    }
    private static LocalDate parseDate(String v){ if(v==null||v.isBlank()) return LocalDate.now(ZoneId.of("Asia/Kolkata")); try{return LocalDate.parse(v);}catch(Exception ignored){} return LocalDate.now(ZoneId.of("Asia/Kolkata")); }
    private static String detectContext(String s){String l=s.toLowerCase(Locale.ROOT); if(l.contains("zomato"))return "ZOMATO"; if(l.contains("swiggy"))return "SWIGGY"; return "";}
}
