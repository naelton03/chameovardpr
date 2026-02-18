package com.pedro.rtmp.utils

interface ConnectCheckerRtmp {
    fun onConnectionStartedRtmp(rtmpUrl: String)
    fun onConnectionSuccessRtmp()
    fun onConnectionFailedRtmp(reason: String)
    fun onNewBitrateRtmp(bitrate: Long)
    fun onDisconnectRtmp()
    fun onAuthErrorRtmp()
    fun onAuthSuccessRtmp()
}
