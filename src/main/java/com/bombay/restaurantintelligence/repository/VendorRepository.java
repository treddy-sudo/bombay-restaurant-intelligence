package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.Vendor; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface VendorRepository extends JpaRepository<Vendor, UUID> { Optional<Vendor> findByNormalizedName(String normalizedName); }
