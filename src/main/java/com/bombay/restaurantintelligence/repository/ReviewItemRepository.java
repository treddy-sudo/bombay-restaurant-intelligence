package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.ReviewItem; import org.springframework.data.jpa.repository.*; import java.util.*;
public interface ReviewItemRepository extends JpaRepository<ReviewItem, UUID> { @EntityGraph(attributePaths={"transaction","transaction.category","transaction.vendor","transaction.employee"}) List<ReviewItem> findTop50ByStatusOrderByCreatedAtDesc(String status); }
