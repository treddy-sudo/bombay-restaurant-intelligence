package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.IngestionJob; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> { boolean existsByChecksumAndStatus(String checksum,String status); boolean existsByChecksum(String checksum); List<IngestionJob> findTop20ByOrderByCreatedAtDesc(); }
