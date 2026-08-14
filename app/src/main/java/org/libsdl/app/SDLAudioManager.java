package org.libsdl.app;

import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;

/* JADX INFO: loaded from: classes4.dex */
public class SDLAudioManager {
    protected static final String TAG = "SDLAudio";
    public static AudioRecord mAudioRecord;
    public static AudioTrack mAudioTrack;

    public static native int nativeSetupJNI();

    public static void initialize() {
        mAudioTrack = null;
        mAudioRecord = null;
    }

    public static int audioOpen(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        String str;
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        int channelConfig = isStereo ? 3 : 2;
        int audioFormat = is16Bit ? 2 : 3;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);
        Log.v(TAG, "SDL audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + (sampleRate / 1000.0f) + "kHz, " + desiredFrames + " frames buffer");
        int desiredFrames2 = Math.max(desiredFrames, ((AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize) - 1) / frameSize);
        if (mAudioTrack == null) {
            str = TAG;
            mAudioTrack = new AudioTrack(3, sampleRate, channelConfig, audioFormat, desiredFrames2 * frameSize, 1);
            if (mAudioTrack.getState() != 1) {
                Log.e(str, "Failed during initialization of Audio Track");
                mAudioTrack = null;
                return -1;
            }
            mAudioTrack.play();
        } else {
            str = TAG;
        }
        Log.v(str, "SDL audio: got " + (mAudioTrack.getChannelCount() < 2 ? "mono" : "stereo") + " " + (mAudioTrack.getAudioFormat() == 2 ? "16-bit" : "8-bit") + " " + (mAudioTrack.getSampleRate() / 1000.0f) + "kHz, " + desiredFrames2 + " frames buffer");
        return 0;
    }

    public static void audioWriteShortBuffer(short[] buffer) {
        if (mAudioTrack == null) {
            Log.e(TAG, "Attempted to make audio call with uninitialized audio!");
            return;
        }
        int i = 0;
        while (i < buffer.length) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                }
            } else {
                Log.w(TAG, "SDL audio: error return from write(short)");
                return;
            }
        }
    }

    public static void audioWriteByteBuffer(byte[] buffer) {
        if (mAudioTrack == null) {
            Log.e(TAG, "Attempted to make audio call with uninitialized audio!");
            return;
        }
        int i = 0;
        while (i < buffer.length) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                }
            } else {
                Log.w(TAG, "SDL audio: error return from write(byte)");
                return;
            }
        }
    }

    public static int captureOpen(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        String str;
        int channelConfig = isStereo ? 3 : 2;
        int audioFormat = is16Bit ? 2 : 3;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);
        Log.v(TAG, "SDL capture: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + (sampleRate / 1000.0f) + "kHz, " + desiredFrames + " frames buffer");
        int desiredFrames2 = Math.max(desiredFrames, ((AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize) - 1) / frameSize);
        if (mAudioRecord == null) {
            str = TAG;
            mAudioRecord = new AudioRecord(0, sampleRate, channelConfig, audioFormat, desiredFrames2 * frameSize);
            if (mAudioRecord.getState() != 1) {
                Log.e(str, "Failed during initialization of AudioRecord");
                mAudioRecord.release();
                mAudioRecord = null;
                return -1;
            }
            mAudioRecord.startRecording();
        } else {
            str = TAG;
        }
        Log.v(str, "SDL capture: got " + (mAudioRecord.getChannelCount() < 2 ? "mono" : "stereo") + " " + (mAudioRecord.getAudioFormat() != 2 ? "8-bit" : "16-bit") + " " + (mAudioRecord.getSampleRate() / 1000.0f) + "kHz, " + desiredFrames2 + " frames buffer");
        return 0;
    }

    public static int captureReadShortBuffer(short[] buffer, boolean blocking) {
        return mAudioRecord.read(buffer, 0, buffer.length);
    }

    public static int captureReadByteBuffer(byte[] buffer, boolean blocking) {
        return mAudioRecord.read(buffer, 0, buffer.length);
    }

    public static void audioClose() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }
    }

    public static void captureClose() {
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }
}
