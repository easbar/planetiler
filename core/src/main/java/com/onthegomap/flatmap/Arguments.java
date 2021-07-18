package com.onthegomap.flatmap;

import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.monitoring.PrometheusStats;
import com.onthegomap.flatmap.monitoring.Stats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Arguments {

  private static final Logger LOGGER = LoggerFactory.getLogger(Arguments.class);

  private final List<Function<String, String>> providers;

  private Arguments(List<Function<String, String>> providers) {
    this.providers = providers;
  }

  public static Arguments fromJvmProperties() {
    return new Arguments(List.of(System::getProperty));
  }

  public static Arguments empty() {
    return new Arguments(List.of());
  }

  public static Arguments of(Map<String, String> map) {
    return new Arguments(List.of(map::get));
  }

  public static Arguments of(String... args) {
    Map<String, String> map = new TreeMap<>();
    for (int i = 0; i < args.length; i += 2) {
      map.put(args[i], args[i + 1]);
    }
    return of(map);
  }

  private String getArg(String key, String defaultValue) {
    String value = getArg(key);
    return value == null ? defaultValue : value;
  }

  private String getArg(String key) {
    String value = null;
    for (int i = 0; i < providers.size() && value == null; i++) {
      value = providers.get(i).apply(key);
    }
    return value == null ? null : value.trim();
  }

  public Envelope bounds(String arg, String description, BoundsProvider defaultBounds) {
    String input = getArg(arg);
    Envelope result;
    if (input == null && defaultBounds != null) {
      // get from input file
      result = defaultBounds.getBounds();
    } else if ("world".equalsIgnoreCase(input)) {
      result = GeoUtils.WORLD_LAT_LON_BOUNDS;
    } else {
      double[] bounds = Stream.of(input.split("[\\s,]+")).mapToDouble(Double::parseDouble).toArray();
      if (bounds.length != 4) {
        throw new IllegalArgumentException("bounds must have 4 coordinates, got: " + input);
      }
      result = new Envelope(bounds[0], bounds[2], bounds[1], bounds[3]);
    }
    logArgValue(arg, description, result);
    return result;
  }

  private void logArgValue(String arg, String description, Object result) {
    LOGGER.debug("  -D" + arg + "=" + result + " (" + description + ")");
  }

  public String get(String arg, String description, String defaultValue) {
    String value = getArg(arg, defaultValue);
    logArgValue(arg, description, value);
    return value;
  }

  public Path file(String arg, String description, Path defaultValue) {
    String value = getArg(arg);
    Path file = value == null ? defaultValue : Path.of(value);
    logArgValue(arg, description, file);
    return file;
  }

  public Path inputFile(String arg, String description, Path defaultValue) {
    Path path = file(arg, description, defaultValue);
    if (!Files.exists(path)) {
      throw new IllegalArgumentException(path + " does not exist");
    }
    return path;
  }

  public boolean get(String arg, String description, boolean defaultValue) {
    boolean value = "true".equalsIgnoreCase(getArg(arg, Boolean.toString(defaultValue)));
    logArgValue(arg, description, value);
    return value;
  }

  public List<String> get(String arg, String description, String[] defaultValue) {
    String value = getArg(arg, String.join(",", defaultValue));
    List<String> results = Stream.of(value.split("[\\s,]+"))
      .filter(c -> !c.isBlank()).toList();
    logArgValue(arg, description, value);
    return results;
  }

  public int threads() {
    String value = getArg("threads", Integer.toString(Runtime.getRuntime().availableProcessors()));
    int threads = Math.max(2, Integer.parseInt(value));
    logArgValue("threads", "num threads", threads);
    return threads;
  }

  public Stats getStats() {
    String prometheus = getArg("pushgateway");
    if (prometheus != null && !prometheus.isBlank()) {
      LOGGER.info("Using prometheus push gateway stats");
      String job = get("pushgateway.job", "prometheus pushgateway job ID", "flatmap");
      Duration interval = duration("pushgateway.interval", "how often to send stats to prometheus push gateway", "15s");
      return new PrometheusStats(prometheus, job, interval);
    } else {
      LOGGER.info("Using in-memory stats");
      return new Stats.InMemory();
    }
  }

  public int integer(String key, String description, int defaultValue) {
    String value = getArg(key, Integer.toString(defaultValue));
    int parsed = Integer.parseInt(value);
    logArgValue(key, description, parsed);
    return parsed;
  }

  public Duration duration(String key, String description, String defaultValue) {
    String value = getArg(key, defaultValue);
    Duration parsed = Duration.parse("PT" + value);
    logArgValue(key, description, parsed.get(ChronoUnit.SECONDS) + " seconds");
    return parsed;
  }
}
