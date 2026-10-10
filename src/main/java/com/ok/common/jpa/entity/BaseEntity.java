package com.ok.common.jpa.entity;

import jakarta.persistence.MappedSuperclass;

import java.time.LocalDateTime;

@MappedSuperclass
public abstract class BaseEntity {
    public abstract Long getId();
    public abstract LocalDateTime getCreatedAt();
    public abstract LocalDateTime getUpdatedAt();
}