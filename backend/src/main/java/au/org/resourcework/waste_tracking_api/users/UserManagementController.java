package au.org.resourcework.waste_tracking_api.users;

import au.org.resourcework.waste_tracking_api.records.PageResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static au.org.resourcework.waste_tracking_api.users.UserData.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {
    private final UserManagementService service;
    public UserManagementController(UserManagementService service){this.service=service;}
    @GetMapping public PageResult<UserResponse> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String query){return service.list(page,size,query);}
    @PostMapping public ResponseEntity<UserResponse> create(@Valid @RequestBody Create input,Principal principal){var user=service.create(input,principal.getName());return ResponseEntity.created(URI.create("/api/admin/users/"+user.id())).body(user);}
    @PutMapping("/{id}/role") public UserResponse role(@PathVariable Long id,@Valid @RequestBody ChangeRole input,Principal principal){return service.role(id,input,principal.getName());}
    @PatchMapping("/{id}/status") public UserResponse status(@PathVariable Long id,@Valid @RequestBody Status input,Principal principal){return service.status(id,input,principal.getName());}
    @PutMapping("/{id}/password") public UserResponse password(@PathVariable Long id,@Valid @RequestBody Password input,Principal principal){return service.password(id,input,principal.getName());}
}
