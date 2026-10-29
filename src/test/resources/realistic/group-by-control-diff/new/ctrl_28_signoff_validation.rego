package grp_signoff

# METADATA
# scope: document
# title: Signature is valid
# description: The signature on the change is present.
# custom:
#   control-id: SEC-28
valid_signature if input.signoff.signature != ""
