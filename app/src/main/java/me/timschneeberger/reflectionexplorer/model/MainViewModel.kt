package me.timschneeberger.reflectionexplorer.model

import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // hold object references for the inspection trail across config changes
    val inspectionStack: MutableList<Any> = mutableListOf()
}

