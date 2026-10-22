package au.org.resourcework.waste_tracking_api.whitegoods;

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

@Entity @Table(name = "whitegoods_record")
@Getter @Setter @NoArgsConstructor
public class WhitegoodsRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate recordDate;
    @Column(name = "whitegoods_count", nullable = false)
    private Integer count;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal kgPerItem;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal estimatedWeight;
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
