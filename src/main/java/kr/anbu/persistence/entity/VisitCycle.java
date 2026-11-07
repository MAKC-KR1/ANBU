package kr.anbu.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "visit_cycles")
@IdClass(VisitCycle.Key.class)
public class VisitCycle {
    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "service_code", nullable = false, length = 30)
    private String serviceCode;

    @Column(name = "cycle_days")
    private Integer cycleDays;

    protected VisitCycle() {
    }

    public VisitCycle(Long organizationId, String serviceCode, Integer cycleDays) {
        this.organizationId = organizationId;
        this.serviceCode = serviceCode;
        this.cycleDays = cycleDays;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public Integer getCycleDays() {
        return cycleDays;
    }

    public void setCycleDays(Integer cycleDays) {
        this.cycleDays = cycleDays;
    }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long organizationId;
        private String serviceCode;

        public Key() {
        }

        public Key(Long organizationId, String serviceCode) {
            this.organizationId = organizationId;
            this.serviceCode = serviceCode;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public void setOrganizationId(Long organizationId) {
            this.organizationId = organizationId;
        }

        public String getServiceCode() {
            return serviceCode;
        }

        public void setServiceCode(String serviceCode) {
            this.serviceCode = serviceCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(organizationId, key.organizationId) && Objects.equals(serviceCode, key.serviceCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, serviceCode);
        }
    }

}
