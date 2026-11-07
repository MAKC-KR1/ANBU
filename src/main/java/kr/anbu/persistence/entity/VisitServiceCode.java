package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "visit_services")
@IdClass(VisitServiceCode.Key.class)
public class VisitServiceCode {
    @Id
    private Long visitId;
    @Id
    @Column(length = 1)
    private String serviceCode;

    public VisitServiceCode() {}

    public Long getVisitId() {
        return visitId;
    }

    public void setVisitId(Long visitId) {
        this.visitId = visitId;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public static class Key implements Serializable {
        private Long visitId;
        private String serviceCode;

        public Key() {}

        public Key(Long visitId, String serviceCode) {
            this.visitId = visitId;
            this.serviceCode = serviceCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(visitId, that.visitId) && Objects.equals(serviceCode, that.serviceCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(visitId, serviceCode);
        }
    }
}
