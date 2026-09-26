package com.bombay.restaurantintelligence.domain;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="employees") public class Employee {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false) private String name;
 @Column(name="normalized_name",nullable=false,unique=true) private String normalizedName;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected Employee(){} public Employee(String name){this.name=name.trim();this.normalizedName=name.trim().toLowerCase();}
 public UUID getId(){return id;} public String getName(){return name;}
}
