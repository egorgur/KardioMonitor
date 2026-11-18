package ru.kardiomonitor.staff.app

import android.app.Application

class StaffApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}