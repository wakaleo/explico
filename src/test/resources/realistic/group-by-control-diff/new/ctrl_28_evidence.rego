package grp_evidence

# METADATA
# scope: document
# title: Evidence is attached
# custom:
#   control-id: SEC-28
evidence_present if count(input.evidence) > 0
