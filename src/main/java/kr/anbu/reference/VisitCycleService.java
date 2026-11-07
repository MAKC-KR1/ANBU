package kr.anbu.reference;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.anbu.persistence.repository.VisitCycleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisitCycleService {
    private final VisitCycleRepository repository;

    public VisitCycleService(VisitCycleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> cycles(long organizationId) {
        Map<String, Integer> cycles = new LinkedHashMap<>();
        cycles.put("A", 30);
        cycles.put("B", 90);
        cycles.put("C", 60);
        cycles.put("D", null);
        repository.findByOrganizationId(organizationId)
                .forEach(cycle -> cycles.put(cycle.getServiceCode(), cycle.getCycleDays()));
        return cycles;
    }
}
