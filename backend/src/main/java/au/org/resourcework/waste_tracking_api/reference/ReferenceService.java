package au.org.resourcework.waste_tracking_api.reference;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReferenceService {
    private final ContainerTypeRepository containers;
    private final MaterialCategoryRepository materials;

    public ReferenceService(ContainerTypeRepository containers, MaterialCategoryRepository materials) {
        this.containers = containers;
        this.materials = materials;
    }

    public List<ContainerResponse> containers() {
        return containers.findByActiveTrueOrderByIdAsc().stream()
                .map(c -> new ContainerResponse(c.getId(), c.getName(), c.getTareWeight().toPlainString())).toList();
    }

    public List<MaterialResponse> materials() {
        return materials.findByActiveTrueOrderByIdAsc().stream()
                .map(m -> new MaterialResponse(m.getId(), m.getName())).toList();
    }

    public record ContainerResponse(Long id, String name, String tareWeight) {}
    public record MaterialResponse(Long id, String name) {}
}
