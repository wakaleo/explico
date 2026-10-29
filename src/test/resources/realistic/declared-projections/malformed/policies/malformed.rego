package pd_malformed

# METADATA
# scope: document
# title: bad actions scalar
# custom:
#   control-id: CTRL-P-BAD1
#   actions: yes
bad_actions_scalar := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad actions no at
# custom:
#   control-id: CTRL-P-BAD2
#   actions:
#     label: action.predicate
bad_actions_no_at := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad actions no label
# custom:
#   control-id: CTRL-P-BAD3
#   actions:
#     at: actions
bad_actions_no_label := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad actions nonstring at
# custom:
#   control-id: CTRL-P-BAD4
#   actions:
#     at: [actions]
#     label: action.predicate
bad_actions_nonstring_at := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad actions empty segment
# custom:
#   control-id: CTRL-P-BAD5
#   actions:
#     at: a..b
#     label: action.predicate
bad_actions_empty_segment := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad annotations no at
# custom:
#   control-id: CTRL-P-BAD6
#   annotations: {}
bad_annotations_no_at := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad annotations nonstring at
# custom:
#   control-id: CTRL-P-BAD7
#   annotations:
#     at: 5
bad_annotations_nonstring_at := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad timing empty
# custom:
#   control-id: CTRL-P-BAD8
#   timing: {}
bad_timing_empty := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}

# METADATA
# scope: document
# title: bad timing nonstring
# custom:
#   control-id: CTRL-P-BAD9
#   timing:
#     dueBy: 5
bad_timing_nonstring := {"compliant": false, "commentary": "No attestation provided", "actions": [{"action": {"predicate": "attest.submit"}}], "annotations": ["not_started"], "dueBy": "2025-01-01T00:00:00Z", "startBefore": "2025-01-02T00:00:00Z"} if {
	not input.form
} else := {"compliant": true, "commentary": "Current", "actions": [], "annotations": [], "dueBy": null, "startBefore": null}
