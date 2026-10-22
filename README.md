# Container Peek

A client-side Fabric mod for Minecraft 1.21.11 that remembers what's in your containers and helps you find things.
It works on any server, because everything happens on your own game.

## Features
- **Look at a container** to see its name and contents next to your crosshair. Container Peek learns them when you open or place containers.
- **Storage Search** (default key **Y**) searches every item in every container you've opened, including shulkers stored in chests. You can sort it, filter it, and click a result to be walked there by waypoint markers that show through walls.
- **Fetch list:** add items with **+ Fetch**, and Container Peek plans a route through your containers (nearest first, no back-tracking). At each stop it shows what to take and highlights those slots.
- **Shulker box tooltips** show the contents as a 9×3 grid of icons.
- **Detailed settings** (Mod Menu or a key you bind): the label's look and position, which container types get labels, the locator, and your saved data.

**Mods panel:** press **Right Shift** on the title screen, the server or world list, or the pause menu, or click
**Mods panel** at the top left of the title screen. It shows the main switches of every PlayWit mod you have (Naughty or Nice,
Base Insurance, Container Peek), so you can turn something off before you join a world, and opens each one's full settings.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runSelfTest` runs a scripted in-game test that saves screenshots to `run/screenshots/`.
