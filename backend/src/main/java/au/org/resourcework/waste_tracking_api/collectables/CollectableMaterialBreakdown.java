package au.org.resourcework.waste_tracking_api.collectables;

import au.org.resourcework.waste_tracking_api.reference.MaterialCategory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter @Setter @NoArgsConstructor
public class CollectableMaterialBreakdown {
    @ManyToOne(optional = false)
    @JoinColumn(name = "material_category_id", nullable = false)
    private MaterialCategory category;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentage;
    @Column(name = "material_weight", nullable = false, precision = 10, scale = 2)
    private BigDecimal materialWeight;

    public CollectableMaterialBreakdown(MaterialCategory category, BigDecimal percentage, BigDecimal materialWeight) {
        this.category = category;
        this.percentage = percentage;
        this.materialWeight = materialWeight;
    }
}
