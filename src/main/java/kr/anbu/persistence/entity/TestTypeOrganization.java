package kr.anbu.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "test_type_organizations")
@IdClass(TestTypeOrganization.Key.class)
public class TestTypeOrganization {
    @Id
    @Column(name = "test_type_id", nullable = false)
    private Long testTypeId;

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    protected TestTypeOrganization() {
    }

    public TestTypeOrganization(Long testTypeId, Long organizationId) {
        this.testTypeId = testTypeId;
        this.organizationId = organizationId;
    }

    public Long getTestTypeId() {
        return testTypeId;
    }

    public void setTestTypeId(Long testTypeId) {
        this.testTypeId = testTypeId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long testTypeId;
        private Long organizationId;

        public Key() {
        }

        public Key(Long testTypeId, Long organizationId) {
            this.testTypeId = testTypeId;
            this.organizationId = organizationId;
        }

        public Long getTestTypeId() {
            return testTypeId;
        }

        public void setTestTypeId(Long testTypeId) {
            this.testTypeId = testTypeId;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public void setOrganizationId(Long organizationId) {
            this.organizationId = organizationId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(testTypeId, key.testTypeId) && Objects.equals(organizationId, key.organizationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(testTypeId, organizationId);
        }
    }

}
