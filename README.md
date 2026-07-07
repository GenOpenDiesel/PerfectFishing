# PerfectFishing

PerfectFishing adds a timing minigame to vanilla/EvenMoreFish fishing.

When a fish bites, players see a moving title/subtitle bar. They must reel in while the marker is inside the green zone. A perfect hit lets the catch continue; a miss cancels the catch.

## Commands

- `/perfectfishing reload`
- `/pfish reload`

## PlaceholderAPI

- `%perfectfishing_successes%`
- `%perfectfishing_fails%`
- `%perfectfishing_attempts%`
- `%perfectfishing_streak%`
- `%perfectfishing_best_streak%`
- `%perfectfishing_success_rate%`
- `%perfectfishing_flags%` — how many times the player has been flagged as suspicious
- `%perfectfishing_suspicious%` — `true`/`false`, whether the player is currently flagged

## Anti-macro detection

The minigame requires reeling exactly when the marker is inside the green zone (random position, narrow window). A screen-reading macro gives itself away by having **both** a near-100% hit rate **and** a very low reaction-time variance at the same time — a human cannot do both. The plugin analyses a rolling window of recent attempts and flags a player once these signals overlap.

On a flag: a log entry, an alert to players holding `perfectfishing.alerts`, and optional console commands. Everything is configurable under the `anticheat` section in `config.yml`.

## Release Automation

Every push to `main` that changes code, resources, `pom.xml`, or the release workflow builds the plugin and creates a GitHub release with the compiled JAR.
