package messages

import rego.v1

deny contains {"msg": msg, "severity": "high"} if {
	input.acl == "public-read"
	msg := "public acl"
}

deny contains msg if {
	input.encrypted == false
	msg := sprintf("bucket %v is unencrypted", [input.name])
}

violation[{"msg": m}] if {
	input.env != "prod"
	m := "not prod"
}
