package kr.anbu.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.anbu.persistence.entity.Dong;
import kr.anbu.persistence.entity.Place;
import kr.anbu.persistence.repository.DongRepository;
import kr.anbu.persistence.repository.PlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("ansan-map")
@Order(10)
public class AnsanMapData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AnsanMapData.class);
    private final DongRepository dongs;
    private final PlaceRepository places;
    private final ObjectMapper mapper;

    public AnsanMapData(DongRepository dongs, PlaceRepository places, ObjectMapper mapper) {
        this.dongs = dongs;
        this.places = places;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        Snapshot snapshot;
        try (var input = new ClassPathResource("reference/ansan-map.json").getInputStream()) {
            snapshot = mapper.readValue(input, Snapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the Ansan map snapshot.", e);
        }

        int inserted = 0;
        for (DongRow row : snapshot.dongs()) {
            Dong dong = dongs.findByNameForUpdate(row.name())
                    .orElseGet(() -> dongs.save(new Dong(row.name(), row.gu())));
            inserted += importPlaces(dong.getId(), row.places());
        }
        log.info("Ansan map data loaded for {} administrative dongs ({} new places).",
                snapshot.dongs().size(), inserted);
    }

    private int importPlaces(long dongId, List<PlaceRow> rows) {
        Map<PlaceKey, Place> existing = new HashMap<>();
        for (Place place : places.findAllByDongIdOrderByIdAsc(dongId)) {
            existing.putIfAbsent(new PlaceKey(place.getName(), place.getAddress()), place);
        }

        int inserted = 0;
        for (PlaceRow row : rows) {
            var key = new PlaceKey(row.name(), row.address());
            Place place = existing.get(key);
            if (place == null) {
                place = new Place(row.name(), dongId, row.address(), row.latitude(), row.longitude());
                place.setPlaceType(row.type());
                places.save(place);
                existing.put(key, place);
                inserted++;
            } else if (place.getPlaceType() == null) {
                place.setPlaceType(row.type());
            }
        }
        return inserted;
    }

    @JsonIgnoreProperties({"boundaryDate", "seniorCenterDate"})
    private record Snapshot(List<DongRow> dongs) {}

    @JsonIgnoreProperties({"code", "bounds"})
    private record DongRow(String name, String gu, List<PlaceRow> places) {}

    private record PlaceRow(String name, String address, BigDecimal latitude, BigDecimal longitude, String type) {}

    private record PlaceKey(String name, String address) {}
}
