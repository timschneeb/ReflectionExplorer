package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView

class InstancesViewModel : ViewModel() {
    var initialLoad: Boolean = true
    // store collapsed group names so UI state survives configuration changes
    val collapsedGroups: MutableSet<String> = mutableSetOf()

    // Persist RecyclerView scroll state (first visible position + pixel offset). Use NO_POSITION (-1) when unset.
    var savedPosition: Int = RecyclerView.NO_POSITION
    var savedOffset: Int = 0
}
