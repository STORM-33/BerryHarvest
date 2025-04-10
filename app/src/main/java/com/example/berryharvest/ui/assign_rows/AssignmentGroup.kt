package com.example.berryharvest.ui.assign_rows

import com.example.berryharvest.data.model.Assignment

data class AssignmentGroup(
    val rowNumber: Int,
    val assignments: List<Assignment>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssignmentGroup
        if (rowNumber != other.rowNumber) return false

        val thisIds = assignments.map { it._id }.toSet()
        val otherIds = other.assignments.map { it._id }.toSet()
        if (thisIds != otherIds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rowNumber
        result = 31 * result + assignments.hashCode()
        return result
    }
}
