package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "visits")
public class Visit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate visitDate;
    private LocalTime visitTime;
    @Column(length = 16)
    private String resultCode;
    private Long placeId;
    private Long organizationId;
    private Long createdBy;
    @Column(length = 2000)
    private String memo;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Visit() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public void setVisitDate(LocalDate visitDate) {
        this.visitDate = visitDate;
    }

    public LocalTime getVisitTime() { return visitTime; }

    public void setVisitTime(LocalTime visitTime) { this.visitTime = visitTime; }

    public String getResultCode() { return resultCode; }

    public void setResultCode(String resultCode) { this.resultCode = resultCode; }

    public Long getPlaceId() {
        return placeId;
    }

    public void setPlaceId(Long placeId) {
        this.placeId = placeId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
