# Embeddium Au Naturel - Project Context

## Overview

This is a fork of Embeddium, a Minecraft performance mod based on Sodium. It provides rendering optimizations for Minecraft clients running on Forge/NeoForge/Fabric.

## Project Structure

- `src/main/` - Main source code
- `src/legacy/` - Legacy API compatibility code
- `src/compat/` - Mod compatibility code (CCL, Immersive Engineering, etc.)
- `buildSrc/` - Gradle build plugins (versioning, Fabric remapper)

## Build System

- **Gradle** with Kotlin DSL
- **Java 17** target
- Uses `net.neoforged.moddev.legacy` plugin
- Custom versioning via `ProjectVersioner`

## Key Technologies

- Minecraft 1.20.1
- Forge 47.3.0 / NeoForge / Fabric
- OpenGL rendering pipeline
- Mixin-based bytecode modification

## Package Structure

- `me.jellysquid.mods.sodium.client` - Core Sodium client code
- `org.embeddedt.embeddium` - Embeddium-specific extensions

## Development Commands

```bash
# Build the mod
./gradlew build

# Run client
./gradlew runClient

# Clean build
./gradlew clean build
```

## License

LGPL v3 - See COPYING and COPYING.LESSER files.
