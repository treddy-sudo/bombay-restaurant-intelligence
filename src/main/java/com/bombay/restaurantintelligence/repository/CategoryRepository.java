package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.Category; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CategoryRepository extends JpaRepository<Category, UUID> { Optional<Category> findByCodeIgnoreCase(String code); }
