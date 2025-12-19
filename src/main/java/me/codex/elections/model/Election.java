package me.codex.elections.model;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class Election {

    public enum Status {
        ACTIVE,
        FINISHED
    }

    public enum Type {
        REGULAR,
        NO_CONFIDENCE
    }

    private final String role;
    private final Instant startedAt;
    private Instant endsAt;
    private Status status = Status.ACTIVE;
    private final LinkedHashSet<UUID> nominees = new LinkedHashSet<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final Map<UUID, String> platforms = new HashMap<>();
    private final Type type;
    private UUID winner;
    private boolean commandsRan = false;
    private boolean announcedFinished = false;

    public Election(String role, Instant endsAt) {
        this(role, endsAt, Type.REGULAR);
    }

    public Election(String role, Instant endsAt, Type type) {
        this.role = role;
        this.startedAt = Instant.now();
        this.endsAt = endsAt;
        this.type = type;
    }

    public String getRole() {
        return role;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Optional<UUID> getWinner() {
        return Optional.ofNullable(winner);
    }

    public void setWinner(UUID winner) {
        this.winner = winner;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Type getType() {
        return type;
    }

    public Duration getRemaining(Instant now) {
        if (status == Status.FINISHED) {
            return Duration.ZERO;
        }
        if (now.isAfter(endsAt)) {
            return Duration.ZERO;
        }
        return Duration.between(now, endsAt);
    }

    public Set<UUID> getNominees() {
        return Collections.unmodifiableSet(nominees);
    }

    public boolean addNominee(UUID nominee) {
        return nominees.add(nominee);
    }

    public void setNominees(List<UUID> nominees) {
        this.nominees.clear();
        this.nominees.addAll(nominees);
    }

    public void setPlatform(UUID nominee, String platform) {
        this.platforms.put(nominee, platform);
    }

    public Optional<String> getPlatform(UUID nominee) {
        return Optional.ofNullable(platforms.get(nominee));
    }

    public Map<UUID, String> getPlatforms() {
        return Collections.unmodifiableMap(platforms);
    }

    public void prunePlatformsForNonNominees() {
        platforms.keySet().removeIf(id -> !nominees.contains(id));
    }

    public void extend(Duration duration) {
        this.endsAt = this.endsAt.plus(duration);
    }

    public boolean isNominee(UUID nominee) {
        return nominees.contains(nominee);
    }

    public Map<UUID, UUID> getVotes() {
        return votes;
    }

    public Map<UUID, Long> getVoteCounts() {
        return votes.values().stream()
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
    }

    public List<UUID> getLeaders() {
        Map<UUID, Long> counts = getVoteCounts();
        long max = counts.values().stream().mapToLong(v -> v).max().orElse(0L);
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public void clearVotesForNonNominees() {
        votes.entrySet().removeIf(entry -> !nominees.contains(entry.getValue()));
    }

    public boolean haveCommandsRun() {
        return commandsRan;
    }

    public void markCommandsRan() {
        this.commandsRan = true;
    }

    public boolean isAnnouncedFinished() {
        return announcedFinished;
    }

    public void markAnnouncedFinished() {
        this.announcedFinished = true;
    }
}
