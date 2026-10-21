package au.org.resourcework.waste_tracking_api.electricalcratepallet;

import au.org.resourcework.waste_tracking_api.auth.AppUser;
import au.org.resourcework.waste_tracking_api.reference.ContainerType;
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

@Entity @Table(name = "electrical_crate_pallet_record")
@Getter @Setter @NoArgsConstructor
public class ElectricalCratePalletRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(nullable = false, unique = true)
    private UUID submissionId;
    @Column(nullable = false)
    private LocalDate recordDate;
    @ManyToOne(optional = false) @JoinColumn(name = "container_type_id")
    private ContainerType containerType;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal grossWeight;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal tareWeight;
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
    @CollectionTable(name = "electrical_crate_pallet_material", joinColumns = @JoinColumn(name = "electrical_crate_pallet_record_id"))
    private List<ElectricalMaterialBreakdown> materials = new ArrayList<>();
}
