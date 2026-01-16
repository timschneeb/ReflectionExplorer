package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.ViewModel

class InspectorViewModel : ViewModel() {
    // store collapsed class names (fully-qualified) so state survives rotation
    val collapsedClasses: MutableSet<String> = mutableSetOf()
}

