package au.org.resourcework.waste_tracking_api.salvage;

import au.org.resourcework.waste_tracking_api.reference.MaterialCategory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter @Setter @NoArgsConstructor
public class SalvageMaterialBreakdown {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private SalvageDirection direction;
    @ManyToOne(optional = false) @JoinColumn(name = "material_category_id")
    private MaterialCategory category;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentage;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal materialWeight;

    public SalvageMaterialBreakdown(SalvageDirection direction, MaterialCategory category,
            BigDecimal percentage, BigDecimal materialWeight) {
        this.direction = direction;
        this.category = category;
        this.percentage = percentage;
        this.materialWeight = materialWeight;
    }
}
