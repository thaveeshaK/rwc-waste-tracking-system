package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class AccountProvisionerIntegrationTests {
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @Test void AUTH_10_repeatedBootstrapCreatesOnlyAbsentAccountsAndPreservesHistory() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
        // Transaction rollback restores the runner's shared Staff account after this test.
        jdbc.update("update app_user set role='ADMIN' where role='STAFF'");
        String staff="bootstrap-staff-"+UUID.randomUUID(),admin="bootstrap-admin-"+UUID.randomUUID();
        String original=UUID.randomUUID().toString(),replacement=UUID.randomUUID().toString();
        long before=users.count();
        var args=new DefaultApplicationArguments();
        new AccountProvisioner(users,encoder,staff,original,admin,original).run(args);
        users.flush();
        var a=users.findByUsername(admin).orElseThrow();var s=users.findByUsername(staff).orElseThrow();
        assertThat(users.count()).isEqualTo(before+2);
        assertThat(a.getRole()).isEqualTo("ADMIN");assertThat(s.getRole()).isEqualTo("STAFF");
        assertThat(encoder.matches(original,a.getPasswordHash())).isTrue();
        assertThat(encoder.matches(original,s.getPasswordHash())).isTrue();
        // Existing account roles deliberately differ from the bootstrap assignments.
        a.setRole("STAFF");s.setRole("ADMIN");users.flush();
        long record=jdbc.queryForObject("insert into whitegoods_record(submission_id,record_date,whitegoods_count,kg_per_item,estimated_weight,created_by,updated_by,created_at,updated_at) values (?, '2001-01-01',1,48,48,?,?,now(),now()) returning id",Long.class,UUID.randomUUID(),a.getId(),s.getId());
        var accounts=jdbc.queryForList("select * from app_user order by id");
        var history=jdbc.queryForMap("select * from whitegoods_record where id=?",record);
        for(int i=0;i<3;i++) {
            new AccountProvisioner(users,encoder,staff,replacement,admin,replacement).run(args);users.flush();
            assertThat(jdbc.queryForList("select * from app_user order by id")).isEqualTo(accounts);
            assertThat(jdbc.queryForMap("select * from whitegoods_record where id=?",record)).isEqualTo(history);
        }
        assertThat(encoder.matches(replacement,a.getPasswordHash())).isFalse();
        assertThat(encoder.matches(replacement,s.getPasswordHash())).isFalse();
    }
}
