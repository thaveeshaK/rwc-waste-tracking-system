package au.org.resourcework.waste_tracking_api.reference;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, Long> {
    List<MaterialCategory> findByActiveTrueOrderByIdAsc();
}
