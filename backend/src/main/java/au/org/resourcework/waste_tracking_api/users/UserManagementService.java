package au.org.resourcework.waste_tracking_api.users;

import au.org.resourcework.waste_tracking_api.auth.*;
import au.org.resourcework.waste_tracking_api.records.PageResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static au.org.resourcework.waste_tracking_api.users.UserData.*;

@Service
@Transactional(readOnly=true)
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementService {
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final Clock clock;
    public UserManagementService(AppUserRepository users,JdbcTemplate jdbc,PasswordEncoder encoder,SessionRegistry sessions,Clock clock) {
        this.users=users;this.jdbc=jdbc;this.encoder=encoder;this.sessions=sessions;this.clock=clock;
    }
    public PageResult<UserResponse> list(int page,int size) {
        PageResult.validate(page,size);long total=users.count();int actual=Math.min(page,PageResult.lastPage(total,size));
        var items=users.findAll(PageRequest.of(actual,size,Sort.by("username").ascending().and(Sort.by("id").ascending())))
            .getContent().stream().map(this::response).toList();
        return new PageResult<>(items,actual,size,total,(total+size-1)/size);
    }
    @Transactional
    public UserResponse create(Create input,String principal) {
        validatePassword(input.password());var actor=lockAndActor(principal);
        String username=input.username().strip();
        if(username.isEmpty()||username.length()>100)bad("Username is required (maximum 100 characters).");
        if(users.existsByUsername(username))throw new ResponseStatusException(HttpStatus.CONFLICT,"This username already exists.");
        var user=new AppUser();user.setUsername(username);user.setPasswordHash(encoder.encode(input.password()));user.setRole(input.role().name());
        user.setCreatedAt(clock.instant());user.setUpdatedAt(user.getCreatedAt());user.setCreatedBy(actor);user.setUpdatedBy(actor);
        return response(users.saveAndFlush(user));
    }
    @Transactional
    public UserResponse role(Long id,ChangeRole input,String principal) {
        var actor=lockAndActor(principal);var user=target(id,input.version());
        protect(user,actor,input.role().name(),user.isActive());
        if(!user.getRole().equals(input.role().name())) {user.setRole(input.role().name());changed(user,actor);expireAfterCommit(user.getUsername());}
        return response(user);
    }
    @Transactional
    public UserResponse status(Long id,Status input,String principal) {
        var actor=lockAndActor(principal);var user=target(id,input.version());
        protect(user,actor,user.getRole(),input.active());
        if(user.isActive()!=input.active()){user.setActive(input.active());changed(user,actor);expireAfterCommit(user.getUsername());}
        return response(user);
    }
    @Transactional
    public UserResponse password(Long id,Password input,String principal) {
        validatePassword(input.password());var actor=lockAndActor(principal);var user=target(id,input.version());
        if(user.getId().equals(actor.getId()))bad("Ask another Admin to reset your password.");
        user.setPasswordHash(encoder.encode(input.password()));changed(user,actor);expireAfterCommit(user.getUsername());return response(user);
    }
    private AppUser lockAndActor(String principal) {
        // Rare, small-organisation account writes are serialised across all application transactions.
        // Counts and changes therefore cannot race to remove the final active Admin.
        jdbc.execute("LOCK TABLE app_user IN SHARE ROW EXCLUSIVE MODE");
        var actor=users.findByUsername(principal).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN));
        if(!actor.isActive()||!actor.getRole().equals("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An active Admin account is required.");
        return actor;
    }
    private AppUser target(Long id,Long version) {
        var user=users.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found."));
        if(!user.getVersion().equals(version))throw new ResponseStatusException(HttpStatus.CONFLICT,"This user has changed. Reload users before saving.");
        return user;
    }
    private void protect(AppUser user,AppUser actor,String role,boolean active) {
        if(user.isActive()&&user.getRole().equals("ADMIN")&&(!active||!role.equals("ADMIN"))&&users.countByRoleAndActiveTrue("ADMIN")<=1)
            bad("At least one active ADMIN account must remain.");
        if(user.getId().equals(actor.getId())&&(!active||!role.equals("ADMIN")))bad("You cannot deactivate or demote your own account.");
    }
    private void changed(AppUser user,AppUser actor) {user.setUpdatedAt(clock.instant());user.setUpdatedBy(actor);users.flush();}
    private void validatePassword(String password) {
        // Preserve the existing non-blank rule; BCrypt accepts at most 72 UTF-8 bytes.
        if(password==null||password.isBlank()||password.getBytes(StandardCharsets.UTF_8).length>72)
            bad("Password must not be blank and must contain at most 72 UTF-8 bytes.");
    }
    private void expireAfterCommit(String username) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for(Object principal:sessions.getAllPrincipals())if(principal instanceof UserDetails user&&user.getUsername().equals(username))
                    sessions.getAllSessions(principal,false).forEach(session->session.expireNow());
            }
        });
    }
    private UserResponse response(AppUser user) {
        return new UserResponse(user.getId(),user.getUsername(),user.getRole(),user.isActive(),user.getVersion(),user.getCreatedAt(),user.getUpdatedAt(),
            user.getCreatedBy()==null?null:user.getCreatedBy().getUsername(),user.getUpdatedBy()==null?null:user.getUpdatedBy().getUsername());
    }
    private void bad(String message) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
