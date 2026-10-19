# METADATA
# title: Storage bucket controls
# description: Rules for object-storage buckets declared in infrastructure plans.
package storage.buckets

import rego.v1

# METADATA
# title: Buckets must be encrypted
# description: Every bucket must have encryption at rest enabled.
# custom:
#   control-id: STO-001
#   frameworks:
#     - SOC 2 CC6.1
#     - ISO 27001 A.8.24
deny contains msg if {
	input.encrypted == false
	msg := sprintf("bucket %v is not encrypted", [input.name])
}

# METADATA
# title: Production buckets must be private
# custom:
#   control-id: STO-002
deny contains msg if {
	input.env == "production"
	input.acl in {"public-read", "public-read-write"}
	msg := sprintf("production bucket %v is publicly readable", [input.name])
}

# METADATA
# title: Retention must be at least 90 days
# custom:
#   control-id: STO-003
deny contains msg if {
	input.retention_days < 90
	msg := "retention is shorter than 90 days"
}
