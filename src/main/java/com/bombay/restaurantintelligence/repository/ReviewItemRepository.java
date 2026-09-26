package com.bombay.restaurantintelligence.repository;

import com.bombay.restaurantintelligence.domain.ReviewItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, UUID> {
    @EntityGraph(attributePaths={"transaction","transaction.category","transaction.vendor","transaction.employee"})
    List<ReviewItem> findTop50ByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);
}
