/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.cameraxbasic.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Color.argb
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.get
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.MainActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.camera.core.FocusMeteringAction

import androidx.camera.core.MeteringPoint

import android.view.MotionEvent

import android.view.View.OnTouchListener
import androidx.core.graphics.alpha
import androidx.core.graphics.translationMatrix


/** Helper type alias used for analysis use case callbacks */
typealias AnalyzerListener = (mask: Bitmap, matrix: Matrix, inv: Matrix) -> Unit
// typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager
    private var transformMatrix : Matrix? = null
    // private val referenceImage = ByteBuffer.allocate(640 * 480)
    private var analyzer : LuminosityAnalyzer? = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /** Volume down button receiver used to trigger shutter
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }
    */

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        // broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    private fun setGalleryThumbnail(uri: Uri) {
        // Run the operations in the view's thread
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                // Remove thumbnail padding
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                        .load(uri)
                        .apply(RequestOptions.circleCropTransform())
                        .into(photoViewButton)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        // broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        //Initialize WindowManager to retrieve display metrics
        windowManager = WindowManager(view.context)

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }

        setUpTapToFocus()
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            lensFacing = when {
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Enable or disable switching between cameras
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation
        Log.d(TAG, "Preview rotation: $rotation")

        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

        analyzer = LuminosityAnalyzer(
            fragmentCameraBinding.viewFinder.width,
            fragmentCameraBinding.viewFinder.height) { mask: Bitmap, matrix: Matrix, inv: Matrix ->
            // Values returned from our analyzer are passed to the attached listener
            // We log image analysis results here - you should do something useful
            // instead!
            fragmentCameraBinding.viewOverlay.processData(mask, matrix, inv)
            transformMatrix = inv
            val res = preview!!.resolutionInfo!!
            /*Log.d(TAG, "Crop rectangle: %d %d %d %d".format(
                res.cropRect.left, res.cropRect.right, res.cropRect.bottom, res.cropRect.top))
            Log.d(TAG, "Rotation and resolution %d %d %d".format(
                res.rotationDegrees, res.resolution.width, res.resolution.height))

             */
        }

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer!!)
                }


        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
            // fragmentCameraBinding.viewFinder.visibility = View.INVISIBLE
            fragmentCameraBinding.viewFinder.setBackgroundColor(Color.parseColor("#ffffff"));

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
                        Toast.makeText(context,
                                "CameraState: Pending Open",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                        Toast.makeText(context,
                                "CameraState: Opening",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                        Toast.makeText(context,
                                "CameraState: Open",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                        Toast.makeText(context,
                                "CameraState: Closing",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Toast.makeText(context,
                                "CameraState: Closed",
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(context,
                                "Stream config error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(context,
                                "Camera in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(context,
                                "Max cameras in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(context,
                                "Other recoverable error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(context,
                                "Camera disabled",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(context,
                                "Fatal error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(context,
                                "Do not disturb mode enabled",
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
                LayoutInflater.from(requireContext()),
                fragmentCameraBinding.root,
                true
        )

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {

            analyzer!!.onPhotoTaken()
            /*
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = Metadata().apply {

                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(cameraExecutor, object :
                        ImageCapture.OnImageCapturedCallback() {

                    private fun ByteBuffer.toByteArray(): ByteArray {
                        rewind()    // Rewind the buffer to zero
                        val data = ByteArray(remaining())
                        get(data)   // Copy the buffer into a byte array
                        return data // Return the byte array
                    }

                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        Log.i(TAG, "Captured image")
                        referenceImage.clear()
                        image.planes[0].buffer.rewind()

                        Log.d(TAG, "Out buffer properties %d %d %d".format(referenceImage.position(),
                        referenceImage.limit(), referenceImage.capacity()))
                        Log.d(TAG, "In buffer properties %d %d %d".format(
                            image.planes[0].buffer.position(), image.planes[0].buffer.limit(),
                            image.planes[0].buffer.capacity()))

                        val arr = image.planes[0].buffer.toByteArray()
                        Log.d(TAG, "Array size %d".format(arr.size))
                        referenceImage.put(arr)
                        image.close()

                        val dbg = referenceImage.toByteArray()
                        for (i in dbg.indices step 1000) {
                            Log.d(TAG, "Pixel %d %d".format(i, dbg[i].toInt()))
                        }
                    }
                })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                                { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }*/
        }

        // Setup for button used to switch cameras
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(
                        requireActivity(), R.id.fragment_container
                ).navigate(CameraFragmentDirections
                        .actionCameraToGallery(outputDirectory.absolutePath))
            }
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(
        _previewX: Int, _previewY: Int,
        listener: (Bitmap, Matrix, Matrix) -> Unit) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<AnalyzerListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        private val previewX = _previewX
        private val previewY = _previewY
        private val prevBuffer = arrayOf(
            ByteBuffer.allocate(480 * 640),
            ByteBuffer.allocate(480 * 640),
            ByteBuffer.allocate(480 * 640)
        )

        private val refBuffer = arrayOf(
            ByteBuffer.allocate(480 * 640),
            ByteBuffer.allocate(480 * 640),
            ByteBuffer.allocate(480 * 640)
        )

        private var refPoint : FloatArray? = null
        private var refValues: FloatArray? = null

        // private var bitmap : Bitmap? = null
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         */
        fun onFrameAnalyzed(listener: AnalyzerListener) = listeners.add(listener)

        fun onPhotoTaken() {
            Log.i(TAG, "Photo taken")
            for (i in 0 until 3) {
                refBuffer[i].clear()
                prevBuffer[i].rewind()
                refBuffer[i].put(prevBuffer[i])
            }
        }

        fun onSetRefPoint(x: Float, y: Float) {
            refPoint = floatArrayOf(x, y)
            refValues = floatArrayOf(
                prevBuffer[0][320*x.toInt() + y.toInt()].toFloat(),
                prevBuffer[1][320*x.toInt() + y.toInt()].toFloat(),
                prevBuffer[2][320*x.toInt() + y.toInt()].toFloat())
        }

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        @RequiresApi(Build.VERSION_CODES.O)
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            val bitmap0 = imageDiff(image, 0, 250.0f)
            val bitmap1 = imageDiff(image, 1, 20.0f)
            val bitmap2 = imageDiff(image, 2, 20.0f)
            val bitmapDiff = Bitmap.createBitmap(image.width/2, image.height/2, Bitmap.Config.ALPHA_8)
            for (x in 0 until bitmap1.width) {
                for (y in 0 until bitmap1.height) {
                    val ydiff = bitmap0.getPixel(x*2, y*2).alpha
                    val udiff = bitmap1.getPixel(x, y).alpha
                    val vdiff = bitmap2.getPixel(x, y).alpha
                    if (false && x % 123 == 0 && y % 123 == 0) {
                        Log.d("KK", "%d %d %d".format(ydiff, udiff, vdiff))
                    }
                    val sq = { i: Int -> i * i }
                    val color = if (sq(ydiff) + sq(udiff) + sq(vdiff) < 2*sq(100)) 0 else 255
                    bitmapDiff.setPixel(x, y, argb(color, 255, 255, 255).toInt())
                }
            }

            var ratio = max(previewX.toFloat() / image.height, previewY.toFloat() / image.width)
            val matrix = Matrix()
            val inv = Matrix()
            matrix.postRotate(270.0f)
            matrix.postScale(1.0f, -1.0f)
            matrix.postScale(ratio, ratio)
            matrix.postScale(2.0f, 2.0f)

            val cropX = max(0.0f, (image.height * ratio - previewX) / 2)
            val cropY = max(0.0f, (image.width * ratio - previewY) / 2)
            matrix.postTranslate(-cropX, -cropY)

            val pts = floatArrayOf(100.0f, 200.0f)
            matrix.mapPoints(pts)
            Log.d("KK", "%f %f".format(pts[0], pts[1]))

            inv.postTranslate(cropX, cropY)
            inv.postScale(1.0f / (2.0f*ratio), 1.0f / (-2.0f*ratio))
            inv.postRotate(90.0f)

            val pts2 = floatArrayOf(1100.0f, 700.0f)
            inv.mapPoints(pts2)
            Log.d("KK", "%f %f".format(pts2[0], pts2[1]))
            // val bitmap1 = Bitmap.createScaledBitmap(bitmap0, scaledW, scaledH, true)
            /*val rotated =
                Bitmap.createBitmap(bitmapDiff, 0, 0, bitmap0.width, bitmap0.height, matrix, true)
            val scaled = Bitmap.createBitmap(
                rotated,
                cropX,
                cropY,
                rotated.width - cropX,
                rotated.height - cropY
            )*/

            // Call all listeners with new value
            listeners.forEach { it(bitmapDiff, matrix, inv) }

            image.close()
        }

        private fun imageDiff(image: ImageProxy, planeIdx: Int, maxDist: Float): Bitmap {
            val buffer = image.planes[planeIdx].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            prevBuffer[planeIdx].clear()
            prevBuffer[planeIdx].put(data)

            // Log.d(TAG, "Plane $planeIdx: Sizes of image: %d %d %d %d %d".format(
            //    data.size, image.width, image.height, image.planes[planeIdx].pixelStride, image.planes[planeIdx].rowStride))

            val ref = refBuffer[planeIdx].toByteArray()
            var W = image.width
            var H = image.height
            if (planeIdx >= 1) {
                W /= 2
                H /= 2
            }
            val bitmap0 = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8)
            var zeros = 0
            var ones = 0

            // var scaledH = (image.height.toFloat() * ratio).toInt()
            // var scaledW = (image.width.toFloat() * ratio).toInt()
            var skipped = 0
            for (i in 0 until data.size) {
                if (ref[i].toFloat() < maxDist) {
                    zeros += 1
                } else {
                    ones += 1
                }
                var dist = abs(data[i].toUByte().toInt() - ref[i].toUByte().toInt())
                if (refPoint != null) {
                    var refX = refPoint!![0].toInt()
                    var refY = refPoint!![1].toInt()
                    if (planeIdx == 0) {
                        refX *= 2
                        refY *= 2
                    }
                    val refI = refY*W + refX
                    dist = abs(data[i].toUByte().toInt() - data[refI].toUByte().toInt())
                }

                if (i < ref.size && dist.toFloat() < maxDist) {
                    skipped += 1
                    continue
                }
                var x = i % W
                var y = i / W
                val color = argb(dist, 255, 255, 255).toInt()
                bitmap0.setPixel(x, y, color)
                if (false && x % 123 == 0 && y % 123 == 0) {
                    Log.d("KK", "%d %d %d %d %d %d".format(planeIdx, x, y, dist, color, bitmap0[x, y].alpha))
                }
                /*
                var outX = (y.toFloat() * ratio).toInt()
                var outY = (x.toFloat() * ratio).toInt()
                var outX1 = ((y+1).toFloat() * ratio).toInt()
                var outY1 = ((x+1).toFloat() * ratio).toInt()
                for (x in outX until outX1)
                    for (y in outY until outY1) {
                        val xx = x - (scaledH - previewX) / 2
                        val yy = y - (scaledW - previewY) / 2
                        if (xx < 0 || xx >= previewX || yy < 0 || yy >= previewY) continue
                        bitmap!!.setPixel(xx, yy, color)
                    }*/
            }
            Log.d(TAG, "Bitmap composition for plane $planeIdx: $skipped $zeros $ones")
            return bitmap0
        }
    }

    private fun setUpTapToFocus() {
        fragmentCameraBinding.viewOverlay.setOnTouchListener(OnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@OnTouchListener true
            }
            Log.d("KK", "Touch. %f %f".format(event.x, event.y))
            val inv = Matrix()
            if (transformMatrix!!.invert(inv)) {
                val arr = floatArrayOf(event.x, event.y)
                transformMatrix!!.mapPoints(arr)
                Log.d("KK", "Touch in camera coordinates %f %f".format(arr[0], arr[1]))
                analyzer!!.onSetRefPoint(arr[0], arr[1])
            }
            return@OnTouchListener false
        })
    }

    companion object {

        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}
