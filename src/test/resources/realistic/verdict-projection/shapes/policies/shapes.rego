package verdict_shapes

# METADATA
# scope: document
# title: Verdict shapes (outcome field)
# custom:
#   control-id: CTRL-V-MIXA
#   verdict:
#     outcome: ok
#     reason: why
shape_a := {"ok": true, "why": "fine"} if {
	input.mode == "good"
} else := {"why": "no outcome field"} if {
	input.mode == "missing-outcome"
} else := {"ok": "yes", "why": "outcome not boolean"} if {
	input.mode == "string-outcome"
}

# METADATA
# scope: document
# title: Verdict shapes (reason field and value type)
# custom:
#   control-id: CTRL-V-MIXB
#   verdict:
#     outcome: ok
#     reason: why
shape_b := {"ok": false} if {
	input.mode == "missing-reason"
} else := {"ok": false, "why": 42} if {
	input.mode == "numeric-reason"
} else := "just a string" if {
	input.mode == "scalar"
}
