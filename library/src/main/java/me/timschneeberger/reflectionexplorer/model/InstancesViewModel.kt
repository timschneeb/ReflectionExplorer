package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.ViewModel

class InstancesViewModel : ViewModel() {
    // store collapsed group names so UI state survives configuration changes
    val collapsedGroups: MutableSet<String> = mutableSetOf()
}
