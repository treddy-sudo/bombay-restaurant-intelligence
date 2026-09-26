package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.AuditLog; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> { List<AuditLog> findByEntityIdOrderByChangedAtDesc(UUID entityId); }
