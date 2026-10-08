package com.ok.common.entity;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_audit_entity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestAuditEntity extends BaseIdAndTime {
    private String name;

    TestAuditEntity(String name) {
        this.name = name;
    }

    void changeName(String name) {
        this.name = name;
    }
}