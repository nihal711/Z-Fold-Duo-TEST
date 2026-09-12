package com.foldduo.hinge.capture;

import android.hardware.HardwareBuffer;

interface ILiveFrameSink {
    int getRequestedDisplayMask();
    void onFrame(int displayId, in HardwareBuffer buffer, long timestampNanos);
}
