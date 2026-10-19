package strs

import rego.v1

# METADATA
# custom:
#   control-id: SB-001
split_rule contains msg if {
	count(split(input.image, ":")) > 1
	msg := "tagged"
}

# METADATA
# custom:
#   control-id: SB-002
trim_rules contains msg if {
	trim_suffix(input.host, ".internal") == "db"
	trim_prefix(input.name, "svc-") == "api"
	trim(input.code, "-") == "x"
	msg := "trimmed"
}

# METADATA
# custom:
#   control-id: SB-003
replace_rule contains msg if {
	replace(input.path, "/", ".") == "a.b"
	msg := "replaced"
}

# METADATA
# custom:
#   control-id: SB-004
substring_rule contains msg if {
	substring(input.sha, 0, 7) == "abc1234"
	msg := "short sha"
}

# METADATA
# custom:
#   control-id: SB-005
numeric_rules contains msg if {
	abs(input.delta) > 5
	ceil(input.ratio) == 2
	floor(input.ratio) == 1
	round(input.ratio) == 2
	msg := "numbers"
}

# METADATA
# custom:
#   control-id: SB-006
collection_rules contains msg if {
	count(array.concat(input.a, input.b)) > 3
	sort(input.tags) == ["a", "b"]
	indexof(input.s, "x") > -1
	json.unmarshal(input.raw) == input.expected
	msg := "collections"
}
