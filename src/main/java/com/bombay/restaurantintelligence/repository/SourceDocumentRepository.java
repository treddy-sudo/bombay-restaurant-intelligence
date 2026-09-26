package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.SourceDocument; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> { boolean existsByFileChecksum(String fileChecksum); Optional<SourceDocument> findByFileChecksum(String fileChecksum); }
