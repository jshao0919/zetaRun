package com.zetarun.app.data

data class AuthStatus(
    val userId: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val configured: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
)
