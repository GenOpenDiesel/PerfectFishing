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

## Release Automation

Every push to `main` that changes code, resources, `pom.xml`, or the release workflow builds the plugin and creates a GitHub release with the compiled JAR.
