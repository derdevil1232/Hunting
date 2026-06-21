# Hunter

An addon for [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) focused on hunting spawners in Minecraft.

## Overview
Hunter is a Meteor Client addon that automates the process of finding, traveling to, and mining mob spawners. It integrates with Baritone for pathfinding and provides various exploration modes to help you locate spawners across the world.

## Features
- **SpawnerHunt Module**: The core module to automate spawner collection.
- **Mob Filtering**: Target specific spawner types (e.g., `minecraft:skeleton`, `minecraft:blaze`).
- **Baritone Integration**: Automatic pathing to detected spawners using Meteor's path manager.
- **Auto-Mining**: Automatically breaks spawners when in range with optional Silk Touch requirement.
- **Exploration Modes**: 
    - **RTP**: Automatically uses `/rtp` and interacts with GUIs to find new areas when no spawners are nearby.
    - **Target Coordinates**: Paths towards specific X/Z coordinates to find spawners along the way.
- **Visuals**: Customizable tracers and boxes to highlight matching spawners.
- **Verification**: Confirms if the spawner was actually picked up after mining and attempts to collect dropped items.

## Requirements
- **Java**: JDK 25
- **Minecraft**: 26.1.2
- **Fabric Loader**: 0.19.2
- **Meteor Client**: 26.1.2-SNAPSHOT
- **Baritone**: Recommended for full automation features.

## Setup & Run
### Building from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/Meteor-Hunting/Hunting.git
   cd SpawnerHunt
   ```
2. Build the JAR:
   ```bash
   ./gradlew build
   ```
   The built JAR will be located in `build/libs/`.

### Development
- To run the Minecraft client with the mod loaded for testing:
  ```bash
  ./gradlew runClient
  ```

## Scripts
- `gradlew build`: Compiles and packages the addon into a JAR file.
- `gradlew runClient`: Launches Minecraft with the addon and Meteor Client.
- `gradlew clean`: Cleans the build directory.

## Project Structure
```text
.
├── gradle/                  # Gradle wrapper and version catalog
│   └── libs.versions.toml   # Dependency versions
├── src/
│   └── main/
│       ├── java/
│       │   └── com/spawner/hunt/
│       │       ├── Hunters.java      # Main Addon Entry point
│       │       └── modules/
│       │           └── SpawnerHunt.java # Core logic
│       └── resources/
│           ├── assets/               # Mod assets (icons, etc.)
│           ├── fabric.mod.json       # Mod metadata
│           └── Hunters.accesswidener # Access Widener for Meteor internals
├── build.gradle.kts         # Build script
├── gradle.properties        # Build properties
└── LICENSE                  # CC0 License
```

## TODOs
- [ ] Update `maven_group` in `gradle.properties` (currently `com.example`).
- [ ] Update `archives_base_name` in `gradle.properties` (currently `addon-template`).
- [ ] Update `rootProject.name` in `settings.gradle.kts` (currently `addon-template`).
- [ ] Add unit tests for spawner detection logic.

## License
This project is licensed under the [CC0 1.0 Universal](LICENSE) license. Feel free to use it for your own projects.

