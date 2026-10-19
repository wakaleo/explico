package samename

import rego.v1

# METADATA
# title: Encrypted
# custom:
#   control-id: SN-001
deny contains msg if {
	input.encrypted == false
	msg := sprintf("bucket %v is not encrypted", [input.name])
}

# METADATA
# title: Private in production
# custom:
#   control-id: SN-002
deny contains msg if {
	input.env == "production"
	input.acl == "public-read"
	msg := "production bucket is public"
}

# METADATA
# title: Admin
# custom:
#   control-id: SN-003
allow if input.admin == true

# METADATA
# title: Owner
# custom:
#   control-id: SN-004
allow if input.owner == true
