package au.org.resourcework.waste_tracking_api.electricaltesting;

import au.org.resourcework.waste_tracking_api.auth.AppUser;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "electrical_testing_tally")
@Getter @Setter @NoArgsConstructor
public class ElectricalTestingTally {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate recordDate;
    @ManyToOne @JoinColumn(name = "category_id")
    private ElectricalTestingCategory category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private TallyType tallyType;
    @Column(nullable = false)
    private Integer count;
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
