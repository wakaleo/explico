package app.billing

# METADATA
# scope: document
# title: Charges are authorised
# custom:
#   control-id: BIL-1
charge_ok if input.charge.authorised

helper_rule if input.helper
