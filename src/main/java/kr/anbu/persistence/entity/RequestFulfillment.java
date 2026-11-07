package kr.anbu.persistence.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "request_fulfillments")
@IdClass(RequestFulfillment.Key.class)
public class RequestFulfillment {
    @Id
    private Long requestId;
    @Id
    private Long visitId;
    private Integer completedCount;

    public RequestFulfillment() {}

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public Long getVisitId() {
        return visitId;
    }

    public void setVisitId(Long visitId) {
        this.visitId = visitId;
    }

    public Integer getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(Integer completedCount) {
        this.completedCount = completedCount;
    }

    public static class Key implements Serializable {
        private Long requestId;
        private Long visitId;

        public Key() {}

        public Key(Long requestId, Long visitId) {
            this.requestId = requestId;
            this.visitId = visitId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(requestId, that.requestId) && Objects.equals(visitId, that.visitId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestId, visitId);
        }
    }
}
