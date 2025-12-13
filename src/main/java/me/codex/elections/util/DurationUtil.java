package me.codex.elections.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationUtil {
    private static final Pattern PART_PATTERN = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);

    private DurationUtil() {
    }

    public static Optional<Duration> parseDuration(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PART_PATTERN.matcher(input.toLowerCase(Locale.ROOT).replace(" ", ""));
        Duration total = Duration.ZERO;
        int matches = 0;
        while (matcher.find()) {
            matches++;
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "d" -> total = total.plusDays(value);
                case "h" -> total = total.plusHours(value);
                case "m" -> total = total.plusMinutes(value);
                case "s" -> total = total.plusSeconds(value);
                default -> {
                }
            }
        }
        return matches == 0 ? Optional.empty() : Optional.of(total);
    }

    public static String format(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) builder.append(days).append("d ");
        if (hours > 0) builder.append(hours).append("h ");
        if (minutes > 0) builder.append(minutes).append("m ");
        if (seconds > 0 && days == 0) builder.append(seconds).append("s");
        return builder.toString().trim();
    }
}
