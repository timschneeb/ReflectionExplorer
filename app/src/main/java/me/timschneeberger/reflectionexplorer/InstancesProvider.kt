package me.timschneeberger.reflectionexplorer

// Some sample classes to inspect
data class Person(var name: String, var age: Int, var address: Address?)

data class Address(var street: String, var city: String, var zip: String)

class Counter(private var count: Int = 0) {
    fun inc() { count++ }
    fun add(n: Int) { count += n }
    fun get(): Int = count
}

object InstancesProvider {
    val instances: Array<Any> = arrayOf(
        Person("Alice", 30, Address("1 Main St", "Springfield", "12345")),
        Person("Bob", 42, null),
        Counter(5),
        Address("2 Broadway", "Metropolis", "54321"),
        "A simple String",
        12345
    )
}

