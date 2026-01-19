package me.timschneeberger.reflectionexplorer.sample

import java.util.Optional

// Some sample classes to inspect
data class Person(var name: String, var age: Int, var address: Address?)

data class Address(var street: String, var city: String, var zip: String)

class Counter(private var count: Int = 0) {
    fun inc() { count++ }
    fun add(n: Int) { count += n }
    fun get(): Int = count
}

enum class Status { NEW, RUNNING, DONE }

class TestData {
    var status = Status.NEW
    val numbers = arrayOf(1, 2, 3, 4, 5)
    val infoMap = mapOf("key1" to "value1", "key2" to "value2")
    val floatNum = 3.14f
    val doubleNum = 2.71828
    val sampleList = listOf("alpha", "beta", "gamma")
    val genericTest = Optional.empty<String>()
}

object TestInstancesProvider {
    val instances: Array<Any> = arrayOf(
        Person("Alice", 30, Address("1 Main St", "Springfield", "12345")),
        Counter(5),
        Address("2 Broadway", "Metropolis", "54321"),
        TestData()
    )
}


