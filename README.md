# Claude Task Queue

Queue up [Claude Code](https://claude.com/claude-code) tasks and let them run in your IntelliJ
terminal while you keep working. Tasks run in a real Claude Code terminal, so you can watch and
step in at any time.

- **TODO / IN PROGRESS / DONE board** — drag a card or press ▶ to start it
- **One column per terminal tab** — different tabs run in parallel, one tab runs in order
- Completion is detected automatically and the next task starts on its own
- Each project has its own queue
- ↻ re-sends an interrupted task to the same conversation
- **Reopen a recent conversation** — pick one from this project and it resumes in a terminal
- **Context usage per conversation** — know when to compact or start fresh

## Requirements

IntelliJ IDEA 2025.3+ and the `claude` CLI on your `PATH`. Both the classic and the reworked
terminal engine work.

## Install

```bash
./gradlew buildPlugin
# Settings → Plugins → ⚙ → Install Plugin from Disk → build/distributions/task-queue-<version>.zip
```

## Build

Gradle needs JVM 17+ (set `JAVA_HOME`, or `org.gradle.java.home` in `~/.gradle/gradle.properties`).

```bash
./gradlew build         # compile + tests
./gradlew runIde        # sandbox IDE with the plugin
```

## License

[MIT](LICENSE)
