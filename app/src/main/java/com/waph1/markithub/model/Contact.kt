package com.waph1.markithub.model

data class Contact(
    val id: String,
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val phoneNumbers: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<TypedAddress> = emptyList(),
    val organization: String = "",
    val note: String = "",
    val birthday: String? = null,
    val websites: List<String> = emptyList(),
    val photoUri: String? = null,
    val sourceUri: String? = null,
    val systemContactId: Long? = null,
    val lastModified: Long = 0L,
    val hash: String = ""
)

/** A value with an Android-style type constant (e.g. Phone.TYPE_MOBILE). */
data class TypedValue(val value: String, val type: Int = 0)

/** A structured postal address. */
data class TypedAddress(
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val type: Int = 0
) {
    fun formatted(): String = listOf(street, city, region, postcode, country)
        .filter { it.isNotBlank() }.joinToString(", ")
}
