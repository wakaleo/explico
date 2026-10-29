package grp_evidence

# METADATA
# scope: document
# title: Evidence is attached
# custom:
#   control-id: SEC-28
evidence_present if count(input.evidence) > 0

# METADATA
# scope: document
# custom:
#   control-id: SEC-28
audit_logged if input.audit.logged
