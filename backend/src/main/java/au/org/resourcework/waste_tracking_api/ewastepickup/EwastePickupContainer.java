package au.org.resourcework.waste_tracking_api.ewastepickup;

import au.org.resourcework.waste_tracking_api.reference.ContainerType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class EwastePickupContainer {
 @ManyToOne(optional=false) @JoinColumn(name="container_type_id") private ContainerType containerType;
 @Column(nullable=false,length=100) private String containerName;
 @Column(nullable=false,precision=10,scale=2) private BigDecimal tarePerUnit;
 @Column(nullable=false) private Integer quantity;
 @Column(nullable=false,precision=10,scale=2) private BigDecimal tareSubtotal;
}
