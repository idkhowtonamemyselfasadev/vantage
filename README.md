# Vantage Client

Vantage is a free, open source client mod for Minecraft 1.21.11 on Fabric (GPL-3.0-or-later).

**Website with the module guide and install steps: [vantageclient.com](https://vantageclient.com)**

## Download

Get the newest jar from [Releases](https://github.com/idkhowtonamemyselfasadev/vantage/releases/latest) or from the [website](https://vantageclient.com). It needs Fabric API. Put the jar in your `mods` folder.

## The source

The code in this repository is the source of release 1.0.37 (see the notes on differences below).

Servers may forbid mods like this and ban you for using them. No warranty.

## Build

You need a JDK 21 and an internet connection for the first build (Gradle downloads itself, Minecraft, the mappings and
the libraries).

    cd mod
    ./gradlew build

The jar is `mod/build/libs/vantage-1.0.37.jar` (not the `-sources` jar). `build` also runs the unit tests and the
formatting check. Every compiler warning is an error.

The in-game check and capture rigs (a second mod, `vantage-rig`, in the original tree under `src/rig`) and their helper
scripts are not part of this repository. Compared with the tree the release was built from, `mod/build.gradle` has the
rig source set and the rig jar tasks taken out. The `-P...` launch options at its end are left over and do nothing
useful here. Some comments and test hooks in the source still mention the rig classes.

## Comparing your build with the release jar

A build from this tree is not byte-identical to the release jar: besides the licence field, the public source replaces two
server addresses with placeholders (`core/Presence.java`: the presence service, `gui/VantageTitleScreen.java`: the quick
join button) and empties one default folder setting. So in a build of your own, Presence and the quick join button do not
reach a real server until you put an address in. To check a release, compare the download's SHA-256 with the one GitHub
shows for the release asset.

## The presence server is not included

Vantage talks to a small "presence" service (the mark on other Vantage users, friends, online count, feedback). Its
server code is **not** in this repository, so what the service does with the data cannot be checked from this source.
What the client sends can be checked, mainly in `core/Presence.java`, `core/Feedback.java` and `core/Updater.java`.

## What the mod sends

Nothing of Vantage's own network traffic starts before you confirm the terms. Confirming also switches Presence on and
switches Auto update on. Like any web request, each one below shows your IP address to the server that receives it.

**Update check** (`github.com`): asks the GitHub API for the latest release when the game starts, and downloads the
newer jar if there is one (installed only if its SHA-256 matches the release). Settings: "Check for updates",
"Auto update". If you run your own build, switch these off if you do not want a later release to replace it.

**Presence** (the Vantage service, only while the "Presence" setting is on):

- at sign-in: your Minecraft name, your UUID and a random server ID for Mojang's join handshake
- about once a minute: the first 16 hex characters of the SHA-256 of the server address (or the word `menu` on the
  title screen and in singleplayer), the token the Vantage service gave you, and the version of the consent you accepted
- reading who else is online on that server hash, and your friend list
- the UUID of a friend when you add, remove or ignore one in the Friends screen
- the owner of Vantage can list the accounts that are online

An unsalted hash of a server address hides little: anyone can hash a list of known addresses and compare.

**Feedback** (only when you press send): your text, the Vantage version and the Minecraft version. If you are signed in
to Presence, its token goes along for rate limiting. No name, UUID or server address is added.

**Mojang**: the join handshake, and name lookups for friend and owner-list UUIDs.

**Your Minecraft session token** is read by `Presence` only to call Mojang's join handshake (`joinServer`). It is not
sent to the Vantage service. (Separately, the auto updater restarts the game on your own computer with the same command
line your launcher used.)

## Licence

Copyright (C) 2026 idkhowtonamemyselfasadev

Vantage is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is
distributed without any warranty; see `LICENSE`.

Additional permission under GNU GPL version 3 section 7: if you modify this program, or any covered work, by linking or
combining it with Minecraft (by Mojang / Microsoft) or with the Sodium mod, the licensors of this program grant you
additional permission to convey the resulting work. This permission does not change the terms of Minecraft or Sodium.

Releases published before this source was released stay under the MIT licence stated inside them.

Jars are in this repository's Releases. The files you see in the source are the 1.0.37 release for Minecraft 1.21.11;
the ports for other game versions are not included.
Fonts and some other material have their own terms, see `THIRD-PARTY.md`.
