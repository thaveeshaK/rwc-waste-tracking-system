package au.org.resourcework.waste_tracking_api.ewastepickup.dto;
import java.time.*;
import java.util.*;
public record EwastePickupResponse(Long id,Long version,UUID submissionId,LocalDate recordDate,String organisation,
 String enteredWeight,String totalTareWeight,String netWeight,List<ContainerResponse> containers,
 String createdBy,Instant createdAt,String updatedBy,Instant updatedAt) {
 public record ContainerResponse(Long containerTypeId,String name,String tarePerUnit,Integer quantity,String tareSubtotal) {}
}
