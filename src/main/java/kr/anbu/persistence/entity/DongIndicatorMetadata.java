package kr.anbu.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "dong_indicator_metadata")
@IdClass(DongIndicatorMetadata.Key.class)
public class DongIndicatorMetadata {
    @Id
    @Column(name = "dong_id", nullable = false)
    private Long dongId;

    @Id
    @Column(name = "indicator_key", nullable = false, length = 100)
    private String indicatorKey;

    @Column(name = "source", length = 500)
    private String source;

    @Column(name = "base_date")
    private LocalDate baseDate;

    @Column(name = "denominator", length = 500)
    private String denominator;

    protected DongIndicatorMetadata() {
    }

    public DongIndicatorMetadata(Long dongId, String indicatorKey, String source,
                                 LocalDate baseDate, String denominator) {
        this.dongId = dongId;
        this.indicatorKey = indicatorKey;
        this.source = source;
        this.baseDate = baseDate;
        this.denominator = denominator;
    }

    public Long getDongId() {
        return dongId;
    }

    public void setDongId(Long dongId) {
        this.dongId = dongId;
    }

    public String getIndicatorKey() {
        return indicatorKey;
    }

    public void setIndicatorKey(String indicatorKey) {
        this.indicatorKey = indicatorKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDate getBaseDate() {
        return baseDate;
    }

    public void setBaseDate(LocalDate baseDate) {
        this.baseDate = baseDate;
    }

    public String getDenominator() {
        return denominator;
    }

    public void setDenominator(String denominator) {
        this.denominator = denominator;
    }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long dongId;
        private String indicatorKey;

        public Key() {
        }

        public Key(Long dongId, String indicatorKey) {
            this.dongId = dongId;
            this.indicatorKey = indicatorKey;
        }

        public Long getDongId() {
            return dongId;
        }

        public void setDongId(Long dongId) {
            this.dongId = dongId;
        }

        public String getIndicatorKey() {
            return indicatorKey;
        }

        public void setIndicatorKey(String indicatorKey) {
            this.indicatorKey = indicatorKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(dongId, key.dongId) && Objects.equals(indicatorKey, key.indicatorKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dongId, indicatorKey);
        }
    }

}
