package au.org.resourcework.waste_tracking_api.electricaltesting;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ElectricalTestingCategoryRepository extends JpaRepository<ElectricalTestingCategory, Long> {
    List<ElectricalTestingCategory> findAllByOrderByDisplayOrderAsc();
    List<ElectricalTestingCategory> findByActiveTrueOrderByDisplayOrderAsc();
}
