package constants

# Reads the input although it has no `if`: its examples are real.
board := [s | some s in input.signoffs; s.ok]

owner := input.owner
