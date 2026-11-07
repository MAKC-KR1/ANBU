package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "visit_reservations")
public class VisitReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long placeId;
    private long organizationId;
    private long testTypeId;
    private long createdBy;
    private LocalDate visitDate;
    private LocalTime visitTime;

    protected VisitReservation() {}

    public VisitReservation(long placeId, long organizationId, long testTypeId, long createdBy,
                            LocalDate visitDate, LocalTime visitTime) {
        this.placeId = placeId;
        this.organizationId = organizationId;
        this.testTypeId = testTypeId;
        this.createdBy = createdBy;
        this.visitDate = visitDate;
        this.visitTime = visitTime;
    }

    public Long getId() { return id; }
    public long getPlaceId() { return placeId; }
    public long getOrganizationId() { return organizationId; }
    public long getTestTypeId() { return testTypeId; }
    public LocalDate getVisitDate() { return visitDate; }
    public LocalTime getVisitTime() { return visitTime; }
}
