package au.org.resourcework.waste_tracking_api.reference;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "container_type")
@Getter @Setter @NoArgsConstructor
public class ContainerType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String name;
    @Column(name = "tare_weight", nullable = false, precision = 10, scale = 2)
    private BigDecimal tareWeight;
    @Column(nullable = false)
    private boolean active = true;
}
