package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // hold object references for the inspection trail across config changes
    val inspectionStack: MutableList<Any> = mutableListOf()

    enum class TriState { DEFAULT, INCLUDE, EXCLUDE }

    data class MemberFilter(
        var visibilityPublic: Boolean = false,
        var visibilityProtected: Boolean = false,
        var visibilityPrivate: Boolean = false,
        var visibilityPackage: Boolean = false, // package-private
        var isStatic: TriState = TriState.DEFAULT,
        var isFinal: TriState = TriState.DEFAULT,
        var kindMethods: Boolean = false,
        var kindFields: Boolean = false
    ) {
        fun anyFiltersActive(): Boolean {
            if (visibilityPublic || visibilityProtected || visibilityPrivate || visibilityPackage) return true
            if (kindFields || kindMethods) return true
            if (isStatic != TriState.DEFAULT) return true
            if (isFinal != TriState.DEFAULT) return true
            return false
        }
    }

    val memberFilter: MemberFilter = MemberFilter()

    // LiveData used to notify observers when the filter object was changed
    val memberFilterLive: MutableLiveData<MemberFilter> = MutableLiveData(memberFilter)
}
