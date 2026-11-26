package com.coke.nestspace.container.api

import android.content.Intent

fun webUrlFromViewIntent(intent: Intent): String? =
    intent.dataString
        ?.takeIf {
            intent.action == Intent.ACTION_VIEW &&
                (it.startsWith("http://") || it.startsWith("https://"))
        }
