package com.nebulaai.app

import android.app.Application

class NebulaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nothing heavy here — DataStore and OkHttp init happen lazily
    }
}
