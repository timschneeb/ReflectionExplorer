package me.timschneeberger.reflectionexplorer.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.timschneeberger.reflectionexplorer.ReflectionExplorer

class SampleMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ReflectionExplorer.instances.clear()
        ReflectionExplorer.instances.addAll(
            TestInstancesProvider.instances +
            this +
            this.layoutInflater
        )
        ReflectionExplorer.launchMainActivity(this)
        finish()
    }
}
