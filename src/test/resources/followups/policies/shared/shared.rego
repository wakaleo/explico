package shared

compliant(commentary, actions, notifications) = {
    "compliant": true,
    "commentary": commentary,
    "actions": [{"action": a, "startBefore": null, "startAfter": null, "dueBy": null} | some a in actions],
    "notifications": notifications,
}

non_compliant(commentary, actions, notifications) = {
    "compliant": false,
    "commentary": commentary,
    "actions": [{"action": a, "startBefore": null, "startAfter": null, "dueBy": null} | some a in actions],
    "notifications": notifications,
}
