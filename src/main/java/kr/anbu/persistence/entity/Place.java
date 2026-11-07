package kr.anbu.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "places")
public class Place {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "dong_id", nullable = false)
    private Long dongId;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "nearest_clinic_km", precision = 10, scale = 3)
    private BigDecimal nearestClinicKm;

    @Column(name = "clinic_distance_source", length = 500)
    private String clinicDistanceSource;

    @Column(name = "clinic_distance_base_date")
    private LocalDate clinicDistanceBaseDate;

    protected Place() {
    }

    public Place(String name, Long dongId, String address,
                 BigDecimal latitude, BigDecimal longitude) {
        this.name = name;
        this.dongId = dongId;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getDongId() {
        return dongId;
    }

    public void setDongId(Long dongId) {
        this.dongId = dongId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public BigDecimal getNearestClinicKm() {
        return nearestClinicKm;
    }

    public void setNearestClinicKm(BigDecimal nearestClinicKm) {
        this.nearestClinicKm = nearestClinicKm;
    }

    public String getClinicDistanceSource() {
        return clinicDistanceSource;
    }

    public void setClinicDistanceSource(String clinicDistanceSource) {
        this.clinicDistanceSource = clinicDistanceSource;
    }

    public LocalDate getClinicDistanceBaseDate() {
        return clinicDistanceBaseDate;
    }

    public void setClinicDistanceBaseDate(LocalDate clinicDistanceBaseDate) {
        this.clinicDistanceBaseDate = clinicDistanceBaseDate;
    }

}
