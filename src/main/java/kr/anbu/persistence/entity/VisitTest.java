package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "visit_tests")
@IdClass(VisitTest.Key.class)
public class VisitTest {
    @Id
    private Long visitId;
    @Id
    private Long testTypeId;
    private Integer testCount;

    public VisitTest() {}

    public Long getVisitId() {
        return visitId;
    }

    public void setVisitId(Long visitId) {
        this.visitId = visitId;
    }

    public Long getTestTypeId() {
        return testTypeId;
    }

    public void setTestTypeId(Long testTypeId) {
        this.testTypeId = testTypeId;
    }

    public Integer getTestCount() {
        return testCount;
    }

    public void setTestCount(Integer testCount) {
        this.testCount = testCount;
    }

    public static class Key implements Serializable {
        private Long visitId;
        private Long testTypeId;

        public Key() {}

        public Key(Long visitId, Long testTypeId) {
            this.visitId = visitId;
            this.testTypeId = testTypeId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(visitId, that.visitId) && Objects.equals(testTypeId, that.testTypeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(visitId, testTypeId);
        }
    }
}
