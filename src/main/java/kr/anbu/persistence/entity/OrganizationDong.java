package kr.anbu.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "organization_dongs")
@IdClass(OrganizationDong.Key.class)
public class OrganizationDong {
    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "dong_id", nullable = false)
    private Long dongId;

    protected OrganizationDong() {
    }

    public OrganizationDong(Long organizationId, Long dongId) {
        this.organizationId = organizationId;
        this.dongId = dongId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public Long getDongId() {
        return dongId;
    }

    public void setDongId(Long dongId) {
        this.dongId = dongId;
    }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long organizationId;
        private Long dongId;

        public Key() {
        }

        public Key(Long organizationId, Long dongId) {
            this.organizationId = organizationId;
            this.dongId = dongId;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public void setOrganizationId(Long organizationId) {
            this.organizationId = organizationId;
        }

        public Long getDongId() {
            return dongId;
        }

        public void setDongId(Long dongId) {
            this.dongId = dongId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(organizationId, key.organizationId) && Objects.equals(dongId, key.dongId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, dongId);
        }
    }

}
