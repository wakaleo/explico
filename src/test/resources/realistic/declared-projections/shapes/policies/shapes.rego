package pd_shapes

# METADATA
# scope: document
# title: Action shapes A
# custom:
#   control-id: CTRL-P-SA
#   actions:
#     at: actions
#     label: action.predicate
shape_a := {"actions": "none"} if {
	input.mode == "actions-not-array"
} else := {"actions": [{"action": {}}]} if {
	input.mode == "actions-missing-label"
} else := {"actions": [{"action": {"predicate": 7}}]} if {
	input.mode == "actions-numeric-label"
}

# METADATA
# scope: document
# title: Action shapes B
# custom:
#   control-id: CTRL-P-SB
#   actions:
#     at: actions
#     label: action.predicate
shape_b := {"actions": [{"action": {"predicate": "good.one"}}, {"action": {}}]} if {
	input.mode == "actions-mixed"
} else := {"commentary": "no actions field"} if {
	input.mode == "actions-field-absent"
} else := "just a string" if {
	input.mode == "actions-scalar-value"
}

# METADATA
# scope: document
# title: Annotation shapes
# custom:
#   control-id: CTRL-P-SC
#   annotations:
#     at: annotations
shape_c := {"annotations": ["a", {"b": 1}]} if {
	input.mode == "ann-mixed"
} else := {"annotations": [1]} if {
	input.mode == "ann-number"
} else := {"annotations": "a"} if {
	input.mode == "ann-not-array"
}

# METADATA
# scope: document
# title: Timing shapes
# custom:
#   control-id: CTRL-P-SD
#   timing:
#     dueBy: dueBy
#     startBefore: startBefore
shape_d := {"dueBy": 5, "startBefore": null} if {
	input.mode == "time-number"
} else := {"dueBy": "", "startBefore": ""} if {
	input.mode == "time-empty"
} else := {"dueBy": null, "startBefore": "2025-06-09T00:00:00Z"} if {
	input.mode == "time-start-only"
}
