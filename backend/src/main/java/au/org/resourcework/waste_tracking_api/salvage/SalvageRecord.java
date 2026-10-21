package au.org.resourcework.waste_tracking_api.salvage;

import au.org.resourcework.waste_tracking_api.auth.AppUser;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "salvage_record")
@Getter @Setter @NoArgsConstructor
public class SalvageRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate recordDate;
    @Column(nullable = false, length = 255)
    private String crew;
    @Column(nullable = false)
    private LocalTime timeUp;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal kgUp;
    @Column(nullable = false)
    private LocalTime timeDown;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal kgDown;
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
    @ElementCollection
    @CollectionTable(name = "salvage_material", joinColumns = @JoinColumn(name = "salvage_record_id"))
    private List<SalvageMaterialBreakdown> materials = new ArrayList<>();
}
