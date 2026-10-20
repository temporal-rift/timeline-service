package io.github.temporalrift.timeline.domain.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The materialised rules of one game: every {@code game.rules.} property with a number, boolean, string or a list of
 * those as its value. Numbers are compared by value, so {@code 2} and {@code 2.0} are the same rule.
 */
public record EffectiveRules(Map<String, Object> properties) {

    private static final String KEY_PREFIX = "game.rules.";
    private static final BigDecimal ES6_PLAIN_LIMIT = new BigDecimal("1e21");

    public EffectiveRules {
        Objects.requireNonNull(properties, "properties");
        var sorted = new TreeMap<String, Object>();
        properties.forEach((key, value) -> {
            if (key == null || !key.startsWith(KEY_PREFIX)) {
                throw new InvalidExecutionContextException(
                        "rules property '" + key + "' must start with " + KEY_PREFIX);
            }
            sorted.put(key, normalized(key, value));
        });
        properties = Collections.unmodifiableMap(sorted);
    }

    /** Lowercase hex SHA-256 of the RFC 8785 canonical form of the properties. */
    public String digest() {
        try {
            var hash =
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The properties as an RFC 8785 JSON object: keys in UTF-16 order, numbers in their shortest ECMAScript form. */
    public String canonicalJson() {
        var json = new StringBuilder("{");
        var separator = "";
        for (var property : properties.entrySet()) {
            json.append(separator);
            string(json, property.getKey());
            json.append(':');
            value(json, property.getValue());
            separator = ",";
        }
        return json.append('}').toString();
    }

    /** The properties flattened for configuration binding: each value as text, each list element under its index. */
    public Map<String, String> bindable() {
        var flat = new LinkedHashMap<String, String>();
        properties.forEach((key, value) -> {
            if (value instanceof List<?> elements) {
                for (var index = 0; index < elements.size(); index++) {
                    flat.put(key + "[" + index + "]", text(elements.get(index)));
                }
            } else {
                flat.put(key, text(value));
            }
        });
        return Collections.unmodifiableMap(flat);
    }

    private static Object normalized(String key, Object value) {
        if (value instanceof List<?> elements) {
            var copy = new ArrayList<>(elements.size());
            elements.forEach(element -> copy.add(scalar(key, element)));
            return Collections.unmodifiableList(copy);
        }
        return scalar(key, value);
    }

    private static Object scalar(String key, Object value) {
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros();
        }
        if (value instanceof Number number) {
            return finite(key, number.toString()).stripTrailingZeros();
        }
        throw new InvalidExecutionContextException(
                "rules property '" + key + "' must be a number, boolean, string or a list of those");
    }

    private static BigDecimal finite(String key, String number) {
        try {
            return new BigDecimal(number);
        } catch (NumberFormatException e) {
            throw new InvalidExecutionContextException("rules property '" + key + "' is not a finite number");
        }
    }

    private static String text(Object value) {
        return value instanceof BigDecimal number ? number.toPlainString() : value.toString();
    }

    private static void value(StringBuilder json, Object value) {
        if (value instanceof List<?> elements) {
            json.append('[');
            var separator = "";
            for (var element : elements) {
                json.append(separator);
                scalar(json, element);
                separator = ",";
            }
            json.append(']');
        } else {
            scalar(json, value);
        }
    }

    private static void scalar(StringBuilder json, Object value) {
        if (value instanceof String text) {
            string(json, text);
        } else if (value instanceof BigDecimal number) {
            json.append(number(number));
        } else {
            json.append(value);
        }
    }

    private static void string(StringBuilder json, String text) {
        json.append('"');
        for (var character : text.toCharArray()) {
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    /** ECMAScript Number-to-String of the nearest double, as RFC 8785 requires. */
    private static String number(BigDecimal value) {
        var number = value.doubleValue();
        if (Double.isInfinite(number)) {
            throw new InvalidExecutionContextException("rules number " + value + " is out of range");
        }
        if (number == 0) {
            return "0";
        }
        var magnitude = new BigDecimal(Double.toString(Math.abs(number))).stripTrailingZeros();
        var digits = magnitude.unscaledValue().toString();
        var count = digits.length();
        var exponent = count - magnitude.scale();
        var sign = number < 0 ? "-" : "";
        if (count <= exponent && magnitude.compareTo(ES6_PLAIN_LIMIT) < 0) {
            return sign + digits + "0".repeat(exponent - count);
        }
        if (0 < exponent && exponent <= 21) {
            return sign + digits.substring(0, exponent) + "." + digits.substring(exponent);
        }
        if (-6 < exponent && exponent <= 0) {
            return sign + "0." + "0".repeat(-exponent) + digits;
        }
        var scientific = exponent - 1;
        var mantissa = count == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (scientific < 0 ? "-" : "+") + Math.abs(scientific);
    }
}
