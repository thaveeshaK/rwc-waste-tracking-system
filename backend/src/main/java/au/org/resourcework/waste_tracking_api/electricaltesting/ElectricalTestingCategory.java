package au.org.resourcework.waste_tracking_api.electricaltesting;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity @Table(name = "electrical_testing_category") @Getter @NoArgsConstructor
public class ElectricalTestingCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private int displayOrder;
    private boolean active;
}
