# Hoard Eye

A client-side Fabric mod for Minecraft 1.21.11 that remembers what's in your containers and helps you find things.
It works on any server, because everything happens on your own game.

By **PlayWit Creations**. (Called Container Peek before 3.0.0: your saved containers and settings carry over by themselves.)

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Get `hoard-eye-<version>+1.21.11.jar` from the [Releases](https://github.com/PlayWitIt/Hoard-Eye/releases) page
   (or build it, see below), and put it in your `.minecraft/mods` folder. If you had Container Peek, remove its jar.

Works well with [Base Insurance](https://github.com/PlayWitIt/Base-Insurance) (rebuild materials are fetched from your
storage) and [Naughty or Nice](https://github.com/PlayWitIt/Naughty-or-Nice). All three share the Mods panel (Right
Shift on the title screen).

## Features
- **Look at a container** to see its name and contents next to your crosshair. Hoard Eye learns them when you open or place containers.
- **Storage Search** (default key **Y**) searches every item in every container you've opened, including shulkers stored in chests. You can sort it, filter it, and click a result to be walked there by waypoint markers that show through walls.
- **Fetch list:** add items with **+ Fetch**, and Hoard Eye plans a route through your containers (nearest first, no back-tracking). At each stop it shows what to take and highlights those slots.
- **Shulker box tooltips** show the contents as a 9×3 grid of icons.
- **Remembers through anti-xray:** servers that hide chests as stone until you're close don't make it forget your storage.
  A container is only forgotten when it's really gone: you see its spot empty up close, the server says it broke, or you
  look right at it and it isn't there. Servers that hide chests you can't see (anti-ESP) don't wipe your storage either,
  and a server menu that pops up instead of a chest is never saved as that chest.
- **Detailed settings** (Mod Menu or a key you bind): the label's look and position, which container types get labels, the locator, and your saved data.

**Mods panel:** press **Right Shift** on the title screen, the server or world list, or the pause menu, or click
**Mods panel** at the top left of the title screen. It shows the main switches of every PlayWit mod you have (Naughty or Nice,
Base Insurance, Hoard Eye), so you can turn something off before you join a world, and opens each one's full settings.
Its **Backups** page saves everything the mods keep, and can keep a copy off the computer: in a folder you pick (one
that Google Drive, OneDrive or Dropbox syncs puts it in the cloud), or uploaded to **Google Drive** or **OneDrive**
after you connect them. One goes every day you play; on a new computer, connect the same place and restore from it.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runSelfTest` runs a scripted in-game test that saves screenshots to `run/screenshots/`.
