package shared

action_container(action) = {"action": action, "startBefore": null, "startAfter": null, "dueBy": null}

wrap_actions(actions) = [action_container(a) | some a in actions]

compliant(commentary, actions, notifications) = {
    "compliant": true, "commentary": commentary,
    "actions": wrap_actions(actions), "notifications": notifications,
}

non_compliant(commentary, actions, notifications) = {
    "compliant": false, "commentary": commentary,
    "actions": wrap_actions(actions), "notifications": notifications,
}

annotated(annotations, decision) = object.union(decision, {"annotations": annotations})

# Echo dueBy / startBefore onto the decision so a declared custom.timing
# projection (FR-5) can read them back from the evaluated object.
with_expiry_timing(decision, due_by, start_before) = object.union(decision, {
    "dueBy": due_by, "startBefore": start_before,
})

within_validity_period(attested_ns, days, now) if {
    now < attested_ns + (days * 24 * 60 * 60 * 1000000000)
}
