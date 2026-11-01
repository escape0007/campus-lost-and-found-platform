package edu.must.campuslostfound;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the operator configuration against the real schema and transaction manager. */
class ModeratorProvisionerTest {
    JdbcTemplate db;
    DataSourceTransactionManager transactions;
    final Clock clock=Clock.systemUTC();
    @BeforeEach void setup() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:provision-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        db=new JdbcTemplate(dataSource);transactions=new DataSourceTransactionManager(dataSource);
        db.update("INSERT INTO accounts(id,username,username_key,display_name,password_hash,role,status,campus_verified,failed_attempts,created_at) VALUES(?,?,?,?,?,'STUDENT','ACTIVE',FALSE,0,?)",
                UUID.randomUUID().toString(),"reviewer","reviewer","Registered Reviewer","test-password-hash",clock.instant().toString());
    }
    private void configure(String grants,String revokes) {
        new ModeratorProvisioner(db,clock,transactions,grants,revokes).run(new DefaultApplicationArguments());
    }
    private String role() {return db.queryForObject("SELECT role FROM accounts WHERE username='reviewer'",String.class);}
    @Test void blankConfigurationHasNoDefaultModeratorOrAccountCreation() {
        configure("","");
        assertThat(role()).isEqualTo("STUDENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM accounts",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_audit",Long.class)).isZero();
    }
    @Test void explicitGrantAndRevokeAreAuditedAndRevokeEndsAssignmentImmediately() {
        configure("Reviewer","");
        assertThat(role()).isEqualTo("MODERATOR");
        String notice=UUID.randomUUID().toString(),time=clock.instant().toString();
        db.update("INSERT INTO notices(id,author_id,author_name,type,title,category,occurrence_date,approximate,area,description,status,hidden,version,material_version,created_at,updated_at) VALUES(?,?,?,'FOUND',?,'KEYS',CURRENT_DATE,FALSE,'LIBRARY',?,'OPEN',FALSE,1,1,?,?)",
                notice,"finder","Finder","Keys","Test notice for assignment",time,time);
        db.update("INSERT INTO review_assignments(id,notice_id,moderator_id,assigned_by,reason,assigned_at,active_target_key) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),notice,"reviewer","reviewer","Accepted report",time,"NOTICE:"+notice);
        configure("","reviewer");
        assertThat(role()).isEqualTo("STUDENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM review_assignments WHERE revoked_at IS NULL OR active_target_key IS NOT NULL",Long.class)).isZero();
        assertThat(db.queryForList("SELECT action FROM account_audit ORDER BY occurred_at",String.class))
                .containsExactly("MODERATOR_GRANTED","MODERATOR_REVOKED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM accounts",Long.class)).isEqualTo(1);
    }
    @Test void unknownOrDisabledAccountFailsAndRollsBackTheEntireGrantBatch() {
        assertThatThrownBy(()->configure("reviewer,missing_user","")).isInstanceOf(IllegalStateException.class);
        assertThat(role()).isEqualTo("STUDENT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_audit",Long.class)).isZero();
        db.update("UPDATE accounts SET status='DISABLED' WHERE username='reviewer'");
        assertThatThrownBy(()->configure("reviewer","")).isInstanceOf(IllegalStateException.class);
        assertThat(role()).isEqualTo("STUDENT");
    }
    @Test void overlappingOrInvalidConfigurationFailsBeforeAnyGrant() {
        assertThatThrownBy(()->configure("reviewer","Reviewer")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->configure("reviewer,invalid-user","")).isInstanceOf(IllegalArgumentException.class);
        assertThat(role()).isEqualTo("STUDENT");
    }
}
