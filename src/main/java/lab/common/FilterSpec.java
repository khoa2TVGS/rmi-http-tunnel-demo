package lab.common;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** A serializable instruction describing one image operation. */
public record FilterSpec(String name, Map<String, Double> params) implements Serializable {

    public FilterSpec {
        name = (name == null || name.isBlank()) ? "grayscale" : name;
        params = (params == null) ? new HashMap<>() : new HashMap<>(params);
    }

    public double param(String key, double fallback) {
        Double v = params.get(key);
        return v == null ? fallback : v;
    }
}
