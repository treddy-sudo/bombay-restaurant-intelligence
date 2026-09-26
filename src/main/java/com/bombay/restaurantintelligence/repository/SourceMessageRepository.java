package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.SourceMessage; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SourceMessageRepository extends JpaRepository<SourceMessage, UUID> { boolean existsBySourceMessageId(String sourceMessageId); }
