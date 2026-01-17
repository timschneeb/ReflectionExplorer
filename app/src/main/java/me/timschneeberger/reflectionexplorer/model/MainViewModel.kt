package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // hold object references for the inspection trail across config changes
    val inspectionStack: MutableList<Any> = mutableListOf()

    // Filter tri-state for member visibility
    enum class TriState { DEFAULT, INCLUDE, EXCLUDE }

    data class MemberFilter(
        var visibilityPublic: TriState = TriState.DEFAULT,
        var visibilityProtected: TriState = TriState.DEFAULT,
        var visibilityPrivate: TriState = TriState.DEFAULT,
        var isStatic: TriState = TriState.DEFAULT,
        var isFinal: TriState = TriState.DEFAULT,
        var kindMethods: TriState = TriState.DEFAULT, // methods
        var kindFields: TriState = TriState.DEFAULT   // fields
    )

    val memberFilter: MemberFilter = MemberFilter()

    // LiveData used to notify observers when the filter object was changed
    val memberFilterLive: MutableLiveData<MemberFilter> = MutableLiveData(memberFilter)
}
