package grp_other

import data.grp_signoff

# METADATA
# scope: document
# title: Retention
# custom:
#   control-id: SEC-30
retention_ok if input.retention.days >= 90

uses_signoff if grp_signoff.signed_off

helper if input.h
