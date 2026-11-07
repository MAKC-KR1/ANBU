package kr.anbu.map;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.Place;
import kr.anbu.persistence.repository.DongRepository;
import kr.anbu.persistence.repository.PlaceRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.geom.util.PolygonExtracter;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FacilityCoverageService {
    private static final double METRES_PER_DEGREE = 111_320;

    private final DongRepository dongs;
    private final PlaceRepository places;
    private final Map<String, Geometry> boundaries;

    public FacilityCoverageService(DongRepository dongs, PlaceRepository places, ObjectMapper mapper) {
        this.dongs = dongs;
        this.places = places;
        this.boundaries = loadBoundaries(mapper);
    }

    public Coverage forDong(long dongId) {
        if (dongId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "dongId는 양수여야 합니다.");
        }
        var dong = dongs.findById(dongId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "동을 찾을 수 없습니다."));
        Geometry boundary = boundaries.get(dong.getName());
        if (boundary == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "동의 지도 경계를 찾을 수 없습니다.");
        }

        Coordinate center = boundary.getEnvelopeInternal().centre();
        double longitudeScale = METRES_PER_DEGREE * Math.cos(Math.toRadians(center.y));
        var toLocal = new AffineTransformation(longitudeScale, 0, -center.x * longitudeScale,
                0, METRES_PER_DEGREE, -center.y * METRES_PER_DEGREE);
        var toWgs84 = new AffineTransformation(1 / longitudeScale, 0, center.x,
                0, 1 / METRES_PER_DEGREE, center.y);

        Map<Coordinate, List<Place>> sites = new LinkedHashMap<>();
        var preparedBoundary = PreparedGeometryFactory.prepare(boundary);
        for (Place place : places.findInspectionTargetsInDong(dongId)) {
            var point = new Coordinate(place.getLongitude().doubleValue(), place.getLatitude().doubleValue());
            if (preparedBoundary.covers(boundary.getFactory().createPoint(point))) {
                Coordinate site = toLocal.transform(point, new Coordinate());
                sites.computeIfAbsent(site, ignored -> new ArrayList<>()).add(place);
            }
        }

        List<Feature> features = new ArrayList<>();
        var writer = new GeoJsonWriter(15);
        writer.setEncodeCRS(false);
        writer.setForceCCW(true);
        if (sites.size() == 1 && sites.values().iterator().next().size() == 1) {
            Place place = sites.values().iterator().next().getFirst();
            features.add(feature(place, writer.write(boundary)));
        } else if (!sites.isEmpty()) {
            Geometry localBoundary = toLocal.transform(boundary);
            for (var region : partition(localBoundary, sites)) {
                features.add(feature(region.place(), writer.write(toWgs84.transform(region.area()))));
            }
        }
        features.sort(Comparator.comparingLong(Feature::id));
        return new Coverage("FeatureCollection", dongId, dong.getName(), features);
    }

    private static List<Region> partition(Geometry boundary, Map<Coordinate, List<Place>> sites) {
        if (sites.size() == 1) {
            return splitSharedSite(boundary, sites.values().iterator().next());
        }
        var builder = new VoronoiDiagramBuilder();
        builder.setSites(sites.keySet());
        builder.setClipEnvelope(boundary.getEnvelopeInternal());
        Geometry diagram = builder.getDiagram(boundary.getFactory());
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < diagram.getNumGeometries(); i++) {
            Geometry cell = diagram.getGeometryN(i);
            Geometry clipped = intersect(boundary, cell);
            regions.addAll(splitSharedSite(clipped, sites.get((Coordinate) cell.getUserData())));
        }
        return regions;
    }

    private static List<Region> splitSharedSite(Geometry cell, List<Place> placesAtSite) {
        if (placesAtSite.size() == 1) {
            return List.of(new Region(placesAtSite.getFirst(), cell));
        }
        Envelope extent = cell.getEnvelopeInternal();
        double previous = extent.getMinX();
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < placesAtSite.size(); i++) {
            double right = i == placesAtSite.size() - 1 ? extent.getMaxX()
                    : longitudeForArea(cell, cell.getArea() * (i + 1) / placesAtSite.size());
            regions.add(new Region(placesAtSite.get(i), clipBand(cell, previous, right)));
            previous = right;
        }
        return regions;
    }

    // At an identical site, west-to-east cuts by cumulative area give every facility a share.
    private static double longitudeForArea(Geometry cell, double area) {
        double west = cell.getEnvelopeInternal().getMinX();
        double low = west;
        double high = cell.getEnvelopeInternal().getMaxX();
        for (int i = 0; i < 40; i++) {
            double cut = (low + high) / 2;
            if (clipBand(cell, west, cut).getArea() < area) {
                low = cut;
            } else {
                high = cut;
            }
        }
        return (low + high) / 2;
    }

    private static Geometry clipBand(Geometry cell, double west, double east) {
        Envelope extent = cell.getEnvelopeInternal();
        Geometry band = cell.getFactory().toGeometry(new Envelope(west, east, extent.getMinY(), extent.getMaxY()));
        return intersect(cell, band);
    }

    private static Geometry intersect(Geometry first, Geometry second) {
        Geometry result = OverlayNGRobust.overlay(first, second, OverlayNG.INTERSECTION);
        if (result instanceof Polygonal) {
            return result;
        }
        List<Polygon> parts = new ArrayList<>();
        PolygonExtracter.getPolygons(result, parts);
        return result.getFactory().createMultiPolygon(GeometryFactory.toPolygonArray(parts));
    }

    private static Map<String, Geometry> loadBoundaries(ObjectMapper mapper) {
        try (var input = new ClassPathResource("reference/ansan-dong-boundaries.geojson").getInputStream()) {
            var reader = new GeoJsonReader();
            Map<String, Geometry> result = new HashMap<>();
            for (JsonNode feature : mapper.readTree(input).path("features")) {
                String name = feature.path("properties").path("name").asText();
                Geometry geometry = reader.read(feature.path("geometry").toString());
                if (name.isBlank() || !(geometry instanceof Polygonal) || geometry.isEmpty() || !geometry.isValid()
                        || result.putIfAbsent(name, geometry) != null) {
                    throw new IllegalStateException("Invalid Ansan map boundary: " + name);
                }
            }
            return Map.copyOf(result);
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Cannot read the Ansan map boundaries.", e);
        }
    }

    private static Feature feature(Place place, String geometry) {
        return new Feature("Feature", place.getId(), new Properties(place.getId(), place.getName()), geometry);
    }

    private record Region(Place place, Geometry area) {}

    public record Coverage(String type, long dongId, String dongName, List<Feature> features) {}

    public record Feature(String type, long id, Properties properties, @JsonRawValue String geometry) {}

    public record Properties(long placeId, String name) {}
}
