package com.hoon.watchtransit.model

import kotlinx.serialization.Serializable

@Serializable
data class TransitCity(
    val code: Int,
    val name: String
)
