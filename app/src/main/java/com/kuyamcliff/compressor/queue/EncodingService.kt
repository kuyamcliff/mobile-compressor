package com.kuyamcliff.compressor.queue

import android.app.Service
import android.content.Intent
import android.os.IBinder

class EncodingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
