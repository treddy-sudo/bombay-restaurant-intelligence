package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.*; import org.springframework.data.jpa.repository.*; import java.time.LocalDate; import java.util.*;
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
 @EntityGraph(attributePaths={"category","vendor","employee"}) List<TransactionEntity> findByStatusAndBusinessDateBetweenOrderByBusinessDateAscCreatedAtAsc(TransactionStatus status, LocalDate from, LocalDate to);
 @EntityGraph(attributePaths={"category","vendor","employee"}) List<TransactionEntity> findTop50ByOrderByCreatedAtDesc();
 boolean existsBySourceMessageId(String sourceMessageId); boolean existsByNormalizedFingerprint(String normalizedFingerprint);
 Optional<TransactionEntity> findFirstByStatusOrderByBusinessDateAsc(TransactionStatus status);
}
