package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.SourceColumnMapping; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SourceColumnMappingRepository extends JpaRepository<SourceColumnMapping,UUID>{Optional<SourceColumnMapping> findFirstBySourceKeyIgnoreCaseAndSourceColumnIgnoreCase(String sourceKey,String sourceColumn);List<SourceColumnMapping> findBySourceKeyIgnoreCaseOrderBySourceColumn(String sourceKey);}
