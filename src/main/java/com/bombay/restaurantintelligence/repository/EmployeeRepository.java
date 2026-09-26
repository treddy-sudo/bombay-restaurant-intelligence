package com.bombay.restaurantintelligence.repository;
import com.bombay.restaurantintelligence.domain.Employee; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface EmployeeRepository extends JpaRepository<Employee, UUID> { Optional<Employee> findByNormalizedName(String normalizedName); }
