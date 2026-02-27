package com.waph1.markithub.model

data class Contact(
    val id: String,
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String = "",
    val note: String = "",
    val photoUri: String? = null,
    val sourceUri: String? = null,
    val systemContactId: Long? = null,
    val lastModified: Long = 0L,
    val hash: String = ""
)
