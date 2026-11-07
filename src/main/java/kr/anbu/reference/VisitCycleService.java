package kr.anbu.reference;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VisitCycleService {
    private final JdbcTemplate jdbc;

    public VisitCycleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Integer> cycles(long organizationId) {
        Map<String, Integer> cycles = new LinkedHashMap<>();
        cycles.put("A", 30);
        cycles.put("B", 90);
        cycles.put("C", 60);
        cycles.put("D", null);
        jdbc.query("SELECT service_code, cycle_days FROM visit_cycles WHERE organization_id = ?",
                rs -> {
                    cycles.put(rs.getString("service_code"), rs.getObject("cycle_days", Integer.class));
                }, organizationId);
        return cycles;
    }
}
