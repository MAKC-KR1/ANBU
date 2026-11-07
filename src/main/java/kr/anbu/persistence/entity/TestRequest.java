package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "test_requests")
public class TestRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sourceVisitId;
    private Long placeId;
    private Long testTypeId;
    private Integer requestedCount;
    private LocalDate requestedDate;
    private Long requestedByOrganizationId;
    private Long recipientOrganizationId;
    @Column(length = 500)
    private String note;
    private String status;
    private Long plannedOrganizationId;
    private LocalDate plannedDate;
    private OffsetDateTime createdAt;

    public TestRequest() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSourceVisitId() {
        return sourceVisitId;
    }

    public void setSourceVisitId(Long sourceVisitId) {
        this.sourceVisitId = sourceVisitId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public void setPlaceId(Long placeId) {
        this.placeId = placeId;
    }

    public Long getTestTypeId() {
        return testTypeId;
    }

    public void setTestTypeId(Long testTypeId) {
        this.testTypeId = testTypeId;
    }

    public Integer getRequestedCount() {
        return requestedCount;
    }

    public void setRequestedCount(Integer requestedCount) {
        this.requestedCount = requestedCount;
    }

    public LocalDate getRequestedDate() {
        return requestedDate;
    }

    public void setRequestedDate(LocalDate requestedDate) {
        this.requestedDate = requestedDate;
    }

    public Long getRequestedByOrganizationId() {
        return requestedByOrganizationId;
    }

    public void setRequestedByOrganizationId(Long requestedByOrganizationId) {
        this.requestedByOrganizationId = requestedByOrganizationId;
    }

    public Long getRecipientOrganizationId() {
        return recipientOrganizationId;
    }

    public void setRecipientOrganizationId(Long recipientOrganizationId) {
        this.recipientOrganizationId = recipientOrganizationId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getPlannedOrganizationId() {
        return plannedOrganizationId;
    }

    public void setPlannedOrganizationId(Long plannedOrganizationId) {
        this.plannedOrganizationId = plannedOrganizationId;
    }

    public LocalDate getPlannedDate() {
        return plannedDate;
    }

    public void setPlannedDate(LocalDate plannedDate) {
        this.plannedDate = plannedDate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
