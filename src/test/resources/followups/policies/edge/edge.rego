package edge_cases

import data.shared.compliant

# A function WITH a condition is never inlined: its value is only defined when the condition holds.
guarded(why) := {"compliant": false, "commentary": why} if {
    why != ""
}

decision = guarded("not good enough") if {
    input.flag
}

# Two definitions of one function: which applies depends on the arguments, so it is never inlined.
twice(a) := {"compliant": true, "commentary": a} if {
    a == "x"
}

twice(a) := {"compliant": false, "commentary": a} if {
    a != "x"
}

second = twice("y") if {
    input.flag
}

# The declared outcome is an input value, not a literal: not a verdict that can be read from the source, so the fields are listed.
verdict_of(ok, why) := {"ok": ok, "why": why}

# METADATA
# custom:
#   verdict: { outcome: ok, reason: why }
third = verdict_of(input.passed, "because") if {
    input.flag
}

# A nested object literal is not modelled: the call stays a plain function call.
nested(a) := {"outer": {"inner": a}}

fourth = nested("v") if {
    input.flag
}
