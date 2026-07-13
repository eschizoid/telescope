# Regenerate:  vhs scripts/demo/head-to-head.tape  ->  img/head-to-head.gif
# Requires: vhs, ttyd, ffmpeg   (brew install vhs ttyd ffmpeg)
# Run from the repo root. The Hide block writes a throwaway `gradlew` shim into a temp dir
# (so the GIF can type the real `./gradlew ...` command) that runs head-to-head.sh — nothing
# named `gradlew` is ever committed; the only committed wrapper is the real one at repo root.
Output img/head-to-head.gif
Set Shell bash
Set FontSize 17
Set Width 1120
Set Height 520
Set Padding 24
Set Theme "Catppuccin Mocha"
Set TypingSpeed 18ms

Hide
Type `D=$(mktemp -d); printf '#!/usr/bin/env bash\nexec "%s/scripts/demo/head-to-head.sh"\n' "$PWD" > "$D/gradlew"; chmod +x "$D/gradlew"; cd "$D"`
Enter
Ctrl+L
Show
Sleep 300ms
Type "./gradlew :examples:mapstruct-vs-telescope:test"
Enter
Sleep 9s
