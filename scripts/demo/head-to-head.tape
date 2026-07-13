# Regenerate:  vhs scripts/demo/head-to-head.tape  ->  img/head-to-head.gif
# Types the real head-to-head command (via the scripts/demo/gradlew shim), then streams
# the curated (real, trimmed) narration line-by-line so it reads like the test running.
Output img/head-to-head.gif
Set Shell bash
Set FontSize 17
Set Width 1120
Set Height 520
Set Padding 24
Set Theme "Catppuccin Mocha"
Set TypingSpeed 18ms

Hide
Type "cd scripts/demo" Enter
Ctrl+L
Show
Sleep 300ms
Type "./gradlew :examples:mapstruct-vs-telescope:test"
Enter
Sleep 9s
