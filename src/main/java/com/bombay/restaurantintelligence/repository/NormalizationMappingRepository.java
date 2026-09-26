package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.NormalizationMapping; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface NormalizationMappingRepository extends JpaRepository<NormalizationMapping, UUID> { Optional<NormalizationMapping> findFirstByRawTermIgnoreCaseAndCanonicalType(String rawTerm,String canonicalType); List<NormalizationMapping> findByCanonicalType(String canonicalType); }
