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
@Table(name = "dongs")
public class Dong {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "gu", length = 100)
    private String gu;

    @Column(name = "elderly_rate", precision = 5, scale = 2)
    private BigDecimal elderlyRate;

    @Column(name = "recipient_rate", precision = 5, scale = 2)
    private BigDecimal recipientRate;

    @Column(name = "single_household_rate", precision = 5, scale = 2)
    private BigDecimal singleHouseholdRate;

    @Column(name = "indicators_base_date")
    private LocalDate indicatorsBaseDate;

    protected Dong() {
    }

    public Dong(String name, String gu) {
        this.name = name;
        this.gu = gu;
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

    public String getGu() {
        return gu;
    }

    public void setGu(String gu) {
        this.gu = gu;
    }

    public BigDecimal getElderlyRate() {
        return elderlyRate;
    }

    public void setElderlyRate(BigDecimal elderlyRate) {
        this.elderlyRate = elderlyRate;
    }

    public BigDecimal getRecipientRate() {
        return recipientRate;
    }

    public void setRecipientRate(BigDecimal recipientRate) {
        this.recipientRate = recipientRate;
    }

    public BigDecimal getSingleHouseholdRate() {
        return singleHouseholdRate;
    }

    public void setSingleHouseholdRate(BigDecimal singleHouseholdRate) {
        this.singleHouseholdRate = singleHouseholdRate;
    }

    public LocalDate getIndicatorsBaseDate() {
        return indicatorsBaseDate;
    }

    public void setIndicatorsBaseDate(LocalDate indicatorsBaseDate) {
        this.indicatorsBaseDate = indicatorsBaseDate;
    }

}
