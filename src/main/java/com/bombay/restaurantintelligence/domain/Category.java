package com.bombay.restaurantintelligence.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="categories")
public class Category {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
 @Column(nullable=false, unique=true) private String code;
 @Column(nullable=false) private String name;
 @Column(name="group_name", nullable=false) private String groupName;
 @Column(nullable=false) private boolean active=true;
 protected Category() {}
 public Category(String code,String name,String groupName){this.code=code;this.name=name;this.groupName=groupName;this.active=true;}
 public UUID getId(){return id;} public String getCode(){return code;} public String getName(){return name;} public String getGroupName(){return groupName;} public boolean isActive(){return active;}
}
