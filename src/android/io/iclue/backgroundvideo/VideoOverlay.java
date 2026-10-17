package io.iclue.backgroundvideo;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("deprecation")
public class VideoOverlay extends ViewGroup implements TextureView.SurfaceTextureListener {
    private static final String TAG = "BACKGROUND_VID_OVERLAY";
    private RecordingState mRecordingState = RecordingState.INITIALIZING;
    private int mCameraId = CameraHelper.NO_CAMERA;
    private Camera mCamera = null;
    private TextureView mPreview;
    private boolean mPreviewAttached = false;
    private MediaRecorder mRecorder = null;
    private boolean mStartWhenInitialized = false;

    private String mFilePath;
    private boolean mRecordAudio = true;
    private int mCameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int mOrientation;
    private int videoBitrate;
    private int audioBitrate;
    //another app took the camera: the part is closed, the recording goes on in a new file when the camera is free
    private static final long RESUME_RETRY_MS = 3000;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mResuming = false;
    private SegmentListener mSegmentListener;

    public interface SegmentListener {
        void onSegment(String filePath);
    }

    public void setSegmentListener(SegmentListener listener) {
        mSegmentListener = listener;
    }

    public VideoOverlay(Context context) {
        super(context);

        this.setClickable(false);
        this.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Create surface to display the camera preview
        mPreview = new TextureView(getContext());
        mPreview.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPreview.setClickable(false);
        mPreview.setSurfaceTextureListener(this);
        attachView();
    }

    public void setCameraFacing(String cameraFace) {
        mCameraFacing = (cameraFace.equalsIgnoreCase("FRONT") ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    public void setRecordAudio(boolean recordAudio) {
        mRecordAudio = recordAudio;
    }

    public void setVideoBitrate(int videoBitrate) {
        this.videoBitrate = videoBitrate;
    }

    public void setAudioBitrate(int audioBitrate) {
        this.audioBitrate = audioBitrate;
    }

    public void Start(String filePath) throws Exception {
        Log.d(TAG, "Start(String filePath)");
        if (this.mRecordingState == RecordingState.STARTED) {
            Log.w(TAG, "Already Recording");
            return;
        }

        if (!TextUtils.isEmpty(filePath)) {
            this.mFilePath = filePath;
        }
        Log.d(TAG, "attachView()");
        attachView();

        if (this.mRecordingState == RecordingState.INITIALIZING) {
            Log.d(TAG, "mRecordingState == RecordingState.INITIALIZING : return");
            this.mStartWhenInitialized = true;
            return;
        }

        if (TextUtils.isEmpty(mFilePath)) {
            throw new IllegalArgumentException("Filename for recording must be set");
        }

        initializeCamera();

        if (mCamera == null) {
            this.detachView();
            throw new NullPointerException("Cannot start recording, we don't have a camera!");
        }

        startRecorder(filePath);
    }

    private void startRecorder(String filePath) throws Exception {
        // Set camera parameters
        Camera.Parameters cameraParameters = mCamera.getParameters();
        Log.d(TAG, "stopPreview()");
        mCamera.stopPreview(); //Apparently helps with freezing issue on some Samsung devices.
        mCamera.unlock();

        try {
            Log.d(TAG, "new MediaRecorder()");
            mRecorder = new MediaRecorder();
            mRecorder.setCamera(mCamera);

            CamcorderProfile profile;
            if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_LOW)) {
                profile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
            } else {
                profile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);
            }

            Camera.Size lowestRes = CameraHelper.getLowestResolution(cameraParameters);
            Log.d(TAG, "getLowestResolution: " + lowestRes.width + "x" + lowestRes.height);
            profile.videoFrameWidth = lowestRes.width;
            profile.videoFrameHeight = lowestRes.height;
            Log.d(TAG, profile.videoFrameWidth + "x" + profile.videoFrameHeight);

            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            if (mRecordAudio) {
                // With audio
                mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            }
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setVideoFrameRate(profile.videoFrameRate);
            mRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
            mRecorder.setVideoEncodingBitRate(videoBitrate);
            if (mRecordAudio) {
                mRecorder.setAudioEncodingBitRate(audioBitrate);
                mRecorder.setAudioChannels(profile.audioChannels);
                mRecorder.setAudioSamplingRate(profile.audioSampleRate);
            }
            mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mRecordAudio) {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }

            mRecorder.setOutputFile(filePath);
            mRecorder.setOrientationHint(mOrientation);
            mRecorder.prepare();
            Log.d(TAG, "Starting recording");
            mRecorder.start();
            Log.d(TAG, "Started recording");
            this.mRecordingState = RecordingState.STARTED;
            Log.d(TAG, "mRecordingState: " + mRecordingState);
        } catch (Exception e) {
            this.releaseCamera();
            Log.e(TAG, "Could not start recording! MediaRecorder Error", e);
            throw e;
        }
    }

    public String Stop() throws IOException {
        Log.d(TAG, "stopRecording called");
        mResuming = false;
        mHandler.removeCallbacks(mResume);

        if (mRecorder != null) {
            MediaRecorder tempRecorder = mRecorder;
            mRecorder = null;
            try {
                tempRecorder.stop();
            } catch (Exception e) {
                //This can occur when the camera failed to start and then stop is called
                Log.e(TAG, "Could not stop recording.", e);
            }
        }

        this.releaseCamera();
        this.detachView();

        return this.mFilePath;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int numChildren = getChildCount();
        if (changed && numChildren > 0) {
            int itemWidth = (r - l) / numChildren;
            for (int i = 0; i < numChildren; i++) {
                View v = getChildAt(i);
                v.layout(itemWidth * i, 0, (i + 1) * itemWidth, b - t);
            }
        }
    }

    private void initializeCamera() {
        Log.d(TAG, "initializeCamera()");
        if (mCamera == null) {
            try {
                mCameraId = CameraHelper.getCameraId(mCameraFacing);
                if (mCameraId != CameraHelper.NO_CAMERA) {
                    mCamera = Camera.open(mCameraId);
                    Log.d(TAG, "Camera opened: " + mCameraId);
                    // Set camera parameters
                    mOrientation = CameraHelper.calculateOrientation((Activity) this.getContext(), mCameraId);
                    Camera.Parameters cameraParameters = mCamera.getParameters();
                    Camera.Size previewSize = CameraHelper.getPreviewSize(cameraParameters);
//                    Camera.Size previewSize = CameraHelper.getLowestResolution(cameraParameters);
                    cameraParameters.setPreviewSize(previewSize.width, previewSize.height);
                    Log.d(TAG, "setPreviewSize: " + previewSize.width + "x" + previewSize.height);
                    cameraParameters.setRotation(mOrientation);
                    cameraParameters.setRecordingHint(true);

                    mCamera.setParameters(cameraParameters);
                    mCamera.setDisplayOrientation(mOrientation);
                    mCamera.setErrorCallback(new Camera.ErrorCallback() {
                        @Override
                        public void onError(int error, Camera camera) {
                            Log.e(TAG, "Camera error: " + error);
                            onCameraLost(error);
                        }
                    });
                    Log.d(TAG, "Camera configured");
                }
            } catch (RuntimeException ex) {
                this.releaseCamera();
                Log.e(TAG, "Unable to open camera. Another application probably has a lock", ex);
            }
        }
    }

    private void onCameraLost(int error) {
        if (mRecordingState != RecordingState.STARTED) return;
        Log.w(TAG, "Camera lost (" + error + "), the part is closed, the recording goes on when the camera is free");
        if (mRecorder != null) {
            MediaRecorder tempRecorder = mRecorder;
            mRecorder = null;
            try {
                tempRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Could not stop the recording after the camera was lost", e);
            }
            try {
                tempRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Could not release the recorder", e);
            }
        }
        releaseLostCamera();
        mResuming = true;
        mHandler.postDelayed(mResume, RESUME_RETRY_MS);
    }

    private final Runnable mResume = new Runnable() {
        @Override
        public void run() {
            if (!mResuming) return;
            String filePath = new File(new File(mFilePath).getParentFile(), System.currentTimeMillis() + ".mp4").getPath();
            try {
                initializeCamera();
                if (mCamera == null) throw new IllegalStateException("the camera is busy");
                SurfaceTexture texture = mPreview.getSurfaceTexture();
                if (texture != null) mCamera.setPreviewTexture(texture);
                mCamera.startPreview();
                startRecorder(filePath);
                mFilePath = filePath;
                mResuming = false;
                Log.i(TAG, "The recording goes on in " + filePath);
                if (mSegmentListener != null) mSegmentListener.onSegment(filePath);
            } catch (Exception e) {
                Log.d(TAG, "The camera is not free yet: " + e.getMessage());
                releaseLostCamera();
                new File(filePath).delete();//a recorder that did not start may leave an empty file
                mHandler.postDelayed(this, RESUME_RETRY_MS);
            }
        }
    };

    //a lost camera throws on stopPreview/lock: only release it
    private void releaseLostCamera() {
        if (mCamera != null) {
            try {
                mCamera.release();
            } catch (Exception e) {
                Log.w(TAG, "Could not release the lost camera", e);
            }
            mCamera = null;
            mCameraId = CameraHelper.NO_CAMERA;
        }
        mRecordingState = RecordingState.STOPPED;
    }

    private void releaseCamera() {
        Log.d(TAG, "releaseCamera()");
        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.lock();
            mCamera.release();
            mCamera = null;
            mCameraId = CameraHelper.NO_CAMERA;
        }
        this.mRecordingState = RecordingState.STOPPED;
        Log.d(TAG, "mRecordingState: " + mRecordingState);
    }

    private void attachView() {
        Log.d(TAG, "attachView()");
        if (!mPreviewAttached && mPreview != null) {
            Log.d(TAG, "addView(mPreview)");
            this.addView(mPreview);
            this.mPreviewAttached = true;
            Log.d(TAG, "attachView() attached");
        }
    }

    private void detachView() {
        Log.d(TAG, "detachView()");
        if (mPreviewAttached && mPreview != null) {
            Log.d(TAG, "removeView(mPreview)");
            this.removeView(mPreview);
            this.mPreviewAttached = false;
            this.mRecordingState = RecordingState.INITIALIZING;
            Log.d(TAG, "mRecordingState: " + mRecordingState);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Creating Texture Created");
        Log.d(TAG, "mRecordingState: " + mRecordingState);

        initializeCamera();

        if (mCamera != null && this.mRecordingState != RecordingState.STARTED) {
            try {
                Log.d(TAG, "setPreviewTexture");
                mCamera.setPreviewTexture(surface);
            } catch (IOException e) {
                Log.e(TAG, "Unable to attach preview to camera!", e);
            }
            Log.d(TAG, "startPreview");
            mCamera.startPreview();
        } else {
            if (mCamera == null) {
                Log.e(TAG, "mCamera == null");
            }
        }

        if (this.mRecordingState == RecordingState.INITIALIZING) {
            this.mRecordingState = RecordingState.STOPPED;//INITIALIZING complete
        }
        Log.d(TAG, "mStartWhenInitialized: " + mStartWhenInitialized);
        Log.d(TAG, "this.mRecordingState != RecordingState.STARTED: " + (this.mRecordingState != RecordingState.STARTED));
        if (mStartWhenInitialized && this.mRecordingState != RecordingState.STARTED) {
            Log.d(TAG, "mRecordingState: " + mRecordingState);
            try {
                Log.d(TAG, "mStartWhenInitialized Start(this.mFilePath)");
                Start(this.mFilePath);
            } catch (Exception ex) {
                Log.e(TAG, "Error start camera", ex);
            }
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private enum RecordingState {INITIALIZING, STARTED, STOPPED}
}
