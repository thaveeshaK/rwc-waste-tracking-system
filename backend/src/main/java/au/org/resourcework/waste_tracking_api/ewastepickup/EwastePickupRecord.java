package au.org.resourcework.waste_tracking_api.ewastepickup;

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

@Entity @Table(name = "ewaste_pickup_record")
@Getter @Setter @NoArgsConstructor
public class EwastePickupRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate recordDate;
    @Column(nullable = false, length = 255)
    private String organisation;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal enteredWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalTareWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal netWeight;
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
    @CollectionTable(name = "ewaste_pickup_container", joinColumns = @JoinColumn(name = "ewaste_pickup_record_id"))
    private List<EwastePickupContainer> containers = new ArrayList<>();
}
