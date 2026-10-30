package rw

import rego.v1

# METADATA
# scope: document
# title: Gold items need a tier
# custom:
#   control-id: RW-1
gold_items_ok if {
    some g in object.get(input, "items", [])
    g.kind == "gold"
    g.tier == "a"
}

# METADATA
# scope: document
# title: A lead must review
# custom:
#   control-id: RW-2
lead_reviewed if {
    some r in object.get(input.request, "reviewers", [])
    r.role == "lead"
}

# METADATA
# scope: document
# title: A list named by the input is not modelled
# custom:
#   control-id: RW-3
extras_ok if {
    some h in object.get(input, input.listName, [])
    h.kind == "extra"
}

normalized(value) := lower(trim(value, " \t\n\r")) if {
    is_string(value)
}

normalized(value) := "" if {
    not is_string(value)
}

# METADATA
# scope: document
# title: The label must read ok
# custom:
#   control-id: RW-4
label_ok if {
    normalized(input.label) == "ok"
}

# METADATA
# scope: document
# title: The text must be exact
# custom:
#   control-id: RW-5
text_exact if {
    input.text == "a\tb\nc\u0001"
    input.name == "café"
}
