# PerWorldAttributes (Maven)

A Paper plugin project targeting Minecraft/Paper 26.1.2.

## Requirements
- JDK 25 (Paper 26.1.x requires Java 25)
- Maven installed and available as `mvn`
- Internet access to resolve the Paper API and Maven dependencies

## Build on Windows PowerShell
Open PowerShell in the folder containing `pom.xml` and run:

```powershell
mvn -U clean package
```

The plugin JAR will be created at:

```text
target\PerWorldAttributes-1.0.1.jar
```

Copy that JAR into the server's `plugins` folder and restart the server.

## Command
- `/pwa reload` — reloads `plugins/PerWorldAttributes/config.yml` and reapplies settings to online players.

## Configuration example

```yaml
worlds:
  world:
    item-modifiers: ALLOW
    attributes:
      movement_speed: 0.2
  arena:
    item-modifiers: DISABLE
    attributes:
      movement_speed: 0.1
```

`attributes` are player BASE values, not additive bonuses. Vanilla movement speed is normally 0.1, so 0.2 doubles the base speed. Attributes not configured in the current world are reset to the baseline captured for that player.

`disabled-modifier-attributes:` suppresses explicit and vanilla default item attribute modifiers on items in a player's inventory in that world. The original modifier data is stored in the item's PDC and restored when the item is handled in an `ALLOW` world. `REMOVE` is accepted as an alias for `DISABLE`. Items inside containers are processed when transferred to a player inventory; this plugin does not rewrite every chest/container in the world.

## Notes
- Test on a backup/staging server first, especially alongside plugins that rewrite item metadata.
- The Paper API dependency is pinned to `26.1.2.build.72-stable`, which was resolvable in the supplied Maven log.
- This archive contains source; I cannot execute a Java 25 Maven build in this environment, so run the build command above to verify it locally.

## Automatic GitHub builds

The repository includes `.github/workflows/build.yml`. GitHub Actions automatically runs `mvn clean package` with Java 25 on every push (including commits pushed to a branch), on pull requests, and when manually started from the Actions tab. If the build succeeds, the plugin JAR is uploaded as an artifact named `PerWorldAttributes` for 14 days.

To enable it, push this project to a GitHub repository. Open the repository's **Actions** tab to view build results and download the JAR from a successful workflow run.
