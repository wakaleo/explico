package pd_diff

# METADATA
# scope: document
# title: Diffed
# custom:
#   control-id: CTRL-P-ONE
#   verdict:
#     outcome: compliant
#     reason: commentary
#   actions:
#     at: actions
#     label: action.predicate
#   annotations:
#     at: annotations
#   timing:
#     dueBy: dueBy
decision := {"compliant": false, "commentary": "c", "actions": [{"action": {"predicate": "p"}}], "annotations": ["s"], "tags": ["t"], "dueBy": "2025-01-01T00:00:00Z", "due": "2025-02-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "ok", "actions": [], "annotations": [], "tags": [], "dueBy": null, "due": null}
