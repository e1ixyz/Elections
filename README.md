# Elections Plugin (Paper 1.21)

Simple elections for arbitrary roles (e.g., Judge) with live scoreboard, tie handling, and console hooks.

## Features
- Single active election at a time with role + countdown.
- Nominate others (no self-nominations), player voting via `/vote`.
- Live sidebar showing role, timer, nominees, vote counts (configurable), vote hint, and results after close.
- Nominee platforms (plans/manifestos): nominees set their plan; everyone can view via command (hint on scoreboard).
- Admin rig command, manual end, and automatic 24h tie extensions between top two until a winner.
- Console commands on win with `%winner%` and `%role%` placeholders.
- Vote of no confidence: players can start a 24h no-confidence vote against the current winner; configurable vote requirement; runs its own console commands on pass.

## Commands
- `/elections` or `/elections help` — show help.
- `/elections status` — show current election info.
- `/elections nominate <player>` — nominate someone.
- `/vote <player>` — vote for a nominee.
- `/elections platform <player>` — view a nominee's platform.
- `/elections platform set <text>` — (nominees) set your platform/plan.
- `/elections scoreboard` — toggle the election sidebar for yourself.
- `/elections noconfidence <winner>` — start a 24h vote of no confidence against the current winner.
- `/elections create <role> <duration>` — start an election (admin).
  - Duration formats: `1d2h`, `6h30m`, `45m`, `90s` etc.
- `/elections rig <player>` — change all votes to a player (admin).
- `/elections end` — end/clear the election and scoreboard (admin).
- `/elections reload` — reload `config.yml` (admin).

## Permissions
- `elections.admin` — required for `create`, `rig`, `end` (default: op).
All other commands are available to everyone.

## Config (`config.yml`)
- `scoreboard.title` — sidebar title.
- `scoreboard.max-candidates` — cap visible nominees (ellipsis if more).
- `scoreboard.show-vote-tip` — show `/vote` hint.
- `scoreboard.show-help-tip` — show `/elections` hint.
- `scoreboard.show-vote-counts` — show numbers next to nominees.
- `commands-on-win` — console commands run when a winner exists. Placeholders: `%winner%`, `%role%`.
- `no-confidence.duration` — default duration for no confidence votes (e.g., `24h`).
- `no-confidence.required-votes` — number of votes needed to pass.
- `no-confidence.commands-on-pass` — console commands run when a no confidence passes. Placeholders: `%target%`, `%role%`.
- `messages.*` — tweak player-facing messages.

## Build
Requirements: Java 21+, Maven.

```sh
mvn -q package
```

Output: `target/elections-1.0.0-SNAPSHOT.jar` (shade-ready, dependencies provided by Paper).

## Usage Notes
- Only one election at a time; use `/elections end` to clear it.
- Players cannot nominate themselves.
- Scoreboard reattaches on join and remains after close until cleared.
# Elections
