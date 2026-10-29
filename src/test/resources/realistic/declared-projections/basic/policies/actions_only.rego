package pd_actions_only

# METADATA
# scope: document
# title: Actions only
# custom:
#   control-id: CTRL-P-ACT
#   actions:
#     at: actions
#     label: action.predicate
decision := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}, {"action": {"predicate": "attest.confirm"}}], "annotations": ["not_started"], "dueBy": null, "startBefore": null} if {
	not input.form
} else := {"compliant": false, "commentary": "Attestation has expired", "actions": [{"action": {"predicate": "attest.renew"}}], "annotations": ["expired", "overdue"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": ""} if {
	input.form.expired
} else := {"compliant": true, "commentary": "Attestation is current", "actions": [], "annotations": [], "dueBy": "2025-07-09T00:00:00Z", "startBefore": "2025-06-09T00:00:00Z"}
