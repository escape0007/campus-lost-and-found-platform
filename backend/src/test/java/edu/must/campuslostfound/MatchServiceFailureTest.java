package edu.must.campuslostfound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static edu.must.campuslostfound.NoticeModels.Actor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchServiceFailureTest {
    @Test
    void databaseFailureDoesNotInventSuggestions() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.query(anyString(), any(RowMapper.class), any())).thenThrow(new DataAccessResourceFailureException("down"));
        MatchService service = new MatchService(db, new ObjectMapper(), Clock.systemUTC());
        assertThatThrownBy(() -> service.suggestions("notice-1", new Actor("user-1", "Student")))
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining("temporarily unavailable")
                .extracting("code").isEqualTo("MATCH_UNAVAILABLE");
    }
}
