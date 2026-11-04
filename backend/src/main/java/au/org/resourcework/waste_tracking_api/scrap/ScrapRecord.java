package au.org.resourcework.waste_tracking_api.scrap;

import au.org.resourcework.waste_tracking_api.auth.AppUser;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "scrap_record")
@Getter @Setter @NoArgsConstructor
public class ScrapRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate reportingMonth;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal ironAndSteelWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal aluminiumWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal otherNonFerrousWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal mixedMetalsWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal ewasteWeight;
    @ManyToOne(optional = false) @JoinColumn(name = "created_by")
    private AppUser createdBy;
    @Column(nullable = false)
    private Instant createdAt;
    @ManyToOne(optional = false) @JoinColumn(name = "updated_by")
    private AppUser updatedBy;
    @Column(nullable = false)
    private Instant updatedAt;
    @ManyToOne @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;
    private Instant deletedAt;
}
