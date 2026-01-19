package me.timschneeberger.reflectionexplorer.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import me.timschneeberger.reflectionexplorer.ReflectionExplorer

class SampleMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample_main)

        ReflectionExplorer.instances.addAll(
            TestInstancesProvider.instances +
            this +
            this.layoutInflater
        )
        ReflectionExplorer.launchMainActivity(this)
        finish()
    }
}
