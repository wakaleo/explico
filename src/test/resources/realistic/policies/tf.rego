package terraform.s3

import rego.v1

# METADATA
# title: Buckets must be encrypted
# custom:
#   control-id: TF-001
deny contains msg if {
	some rc in input.resource_changes
	rc.type == "aws_s3_bucket"
	rc.change.after.server_side_encryption_configuration == null
	msg := sprintf("%s lacks encryption", [rc.address])
}

deny contains msg if {
	rc := input.resource_changes[_]
	rc.type == "aws_s3_bucket"
	rc.change.actions[_] == "delete"
	msg := sprintf("Bucket %s (%s) would be deleted", [rc.address, rc.change.before.region])
}

deny contains {"msg": msg, "severity": "high"} if {
	some rc in input.resource_changes
	rc.change.after.acl in {"public-read", "public-read-write"}
	msg := "public acl"
}

violation[{"msg": m}] if {
	input.tags.env != "prod"
	m := "not prod"
}

tagged if {
	input.tags[_] == "x"
}

deny contains msg if {
	not tagged
	count([r | some r in input.resource_changes; r.type == "aws_s3_bucket"]) > 3
	msg := "too many"
}
